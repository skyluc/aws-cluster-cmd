package org.skyluc.aws.conf

import org.skyluc.aws.model.EC2Client
import com.amazonaws.regions.Regions
import scala.util.Try
import com.amazonaws.services.ec2.model.DescribeImagesRequest
import com.amazonaws.services.ec2.model.Filter
import scala.util.Success
import org.skyluc.aws.conf.RawConfiguration.ParseException
import scala.util.Failure
import org.skyluc.aws.utils.TryUtils
import com.amazonaws.services.ec2.model.InstanceType
import java.io.File
import org.skyluc.aws.conf.Launch.GroupWithInstances
import org.skyluc.aws.model.InstanceField
import scala.annotation.tailrec
import scala.util.Success
import org.skyluc.aws.conf.Launch.GroupWithInstances

case class Configuration(
  name: String,
  region: Regions,
  keyName: String,
  keyFile: File,
  images: List[Image],
  groups: List[Group],
  postLaunchSteps: List[Step])

object Configuration {

  val StupidVarFindingRegex = "\\$\\{[^\\}]*\\}".r
  val VarRegex = "([^/]+)/(\\d+)/([^/]+)".r

  def apply(raw: RawConfiguration, client: EC2Client): Try[Configuration] = {
    for {
      region <- regionFrom(raw)
      images <- TryUtils.map(raw.images) { checkImage(_, client) }
      groups <- TryUtils.map(raw.groups) { checkGroup(_, images) }
      postLaunchSteps <- TryUtils.map(raw.postLaunchSteps) { checkStep(_, groups) }
      keyName <- checkKeyName(raw.keyName, client)
      keyFile <- checkKeyFile(raw.keyFile)
    } yield {
      Configuration(
        raw.name,
        region,
        raw.keyName,
        new File(raw.keyFile),
        images,
        groups,
        postLaunchSteps)
    }
  }

  private def checkImage(rawImage: RawImage, client: EC2Client): Try[Image] = {
    client.imageWithId(rawImage.id)
      .map(i => Success(Image(rawImage.name, rawImage.id, rawImage.username)))
      .getOrElse(Failure(new ParseException(s"Image ${rawImage.id} doesn't exists in EC2")))
  }

  private def checkGroup(rawGroup: RawGroup, images: List[Image]): Try[Group] = {
    for {
      image <- imageWithName(rawGroup.image, images)
      instanceType <- instanceTypeFor(rawGroup.instanceType)
    } yield {
      Group(rawGroup.name, image, instanceType, rawGroup.nbOfInstances, rawGroup.spotPrice)
    }
  }

  private def checkStep(rawStep: RawStep, groups: List[Group]): Try[Step] = {
    for {
      group <- groupWithName(rawStep.group, groups)
      cmd <- checkCmd(rawStep.cmd, groups)
    } yield {
      Step(group, cmd)
    }
  }

  private def checkCmd(cmd: String, groups: List[Group]): Try[List[CmdElement]] = {
    trait SubString {
      val value: String
    }
    
    case class SimpleSubString(override val value: String) extends SubString
    case class VarSubString(override val value: String) extends SubString
    
    val matches = StupidVarFindingRegex.findAllMatchIn(cmd)

    @tailrec
    def extractParts(endOfLast: Int, acc: List[SubString]): List[SubString] = {
      if (matches.hasNext) {
        val m = matches.next
        val matched = m.matched
        val value = matched.substring(2, matched.length() - 1)
        if (m.start == endOfLast) {
          extractParts(m.end, VarSubString(value) :: acc)
        } else {
          extractParts(m.end, VarSubString(value) :: SimpleSubString(cmd.substring(endOfLast, m.start)) :: acc)
        }
      } else {
        val res = if (endOfLast == cmd.length()) {
          acc
        } else {
          SimpleSubString(cmd.substring(endOfLast)) :: acc
        }
        res.reverse
      }
    }
    
    val parts = extractParts(0, Nil)
    
    TryUtils.map(parts){
      case SimpleSubString(value) =>
        Success(StringCmdElement(value))
      case VarSubString(value) =>
        checkVar(value, groups)
    }
  }
  
  private def checkVar(varString: String, groups: List[Group]): Try[VarCmdElement] = {
    varString match {
      case VarRegex(g, i, f) =>
        for {
          group <- groupWithName(g, groups)
          field <- InstanceField(f).map{Success(_)}.getOrElse(Failure(ParseException(s"Unknown field: $f")))
        } yield {
          VarCmdElement(group, i.toInt, field)
        }
      case _ =>
        Failure(new ParseException(s"Didn't recognized the pattern group/instanceNd/field in var id: $varString"))
    }
  }

  private def checkKeyName(keyName: String, client: EC2Client): Try[String] = {
    client.keyWithName(keyName)
      .map(k => Success(keyName))
      .getOrElse(Failure(new ParseException(s"key pair ${keyName} doesn't exists in EC2.")))
  }

  private def checkKeyFile(keyFilePath: String): Try[File] = {
    val keyFile = new File(keyFilePath)
    if (keyFile.isFile()) {
      Success(keyFile)
    } else {
      Failure(new ParseException(s"${keyFile.getAbsolutePath} doesn't exist, or is not a file"))
    }
  }

  private def imageWithName(name: String, images: List[Image]): Try[Image] = {
    images.find(_.name == name)
      .map(i => Success(i))
      .getOrElse(Failure(new ParseException(s"Image with name '$name' not defined in configuration")))
  }

  private def groupWithName(name: String, groups: List[Group]): Try[Group] = {
    groups.find(_.name == name)
      .map(g => Success(g))
      .getOrElse(Failure(new ParseException(s"Group with name '$name' not defined in configuration")))
  }

  def regionFrom(rawConf: RawConfiguration): Try[Regions] = {
    Try(Regions.fromName(rawConf.region))
  }

  def instanceTypeFor(instanceType: String): Try[InstanceType] = {
    Try(InstanceType.fromValue(instanceType))
  }
}

case class Image(
  name: String,
  id: String,
  username: String)

case class Group(
  name: String,
  image: Image,
  instanceType: InstanceType,
  nbOfInstances: Int,
  spotPrice: Option[Double])

case class Step(
  group: Group,
  cmd: List[CmdElement]) {
  
  def resolveCmdWith(groups: Map[String, GroupWithInstances]): String = {
    cmd.map{_.valueBasedOn(groups)}.mkString
  }
}

trait CmdElement {
  def valueBasedOn(groups: Map[String, GroupWithInstances]): String
}

case class StringCmdElement(value: String) extends CmdElement {

  def valueBasedOn(groups: Map[String, GroupWithInstances]): String = value

}

case class VarCmdElement(group: Group, instanceNb: Int, field: InstanceField) extends CmdElement {

  def valueBasedOn(groups: Map[String, GroupWithInstances]): String = {
    field.valueFrom(groups(group.name).instances(instanceNb))
  }

}
