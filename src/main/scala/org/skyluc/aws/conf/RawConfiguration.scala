package org.skyluc.aws.conf

import scala.util.Try
import com.typesafe.config.ConfigFactory
import java.io.File
import scala.util.Failure
import com.typesafe.config.Config
import scala.util.Success

case class RawConfiguration(
  name: String,
  region: String,
  keyName: String,
  keyFile: String,
  images: List[RawImage],
  groups: List[RawGroup],
  postLaunchSteps: List[RawStep])

object RawConfiguration {
  def apply(filePath: String): Try[RawConfiguration] = {
    val file = new File(filePath)
    if (!file.isFile()) {
      Failure(ParseException(s"$filePath doesn't exists or is not a file"))
    } else {
      Try {
        val config = ConfigFactory.parseFile(file)
        val cluster = config.getConfig("cluster")

        val name = cluster.getString("name")
        val region = cluster.getString("region")
        val keyName = cluster.getString("keyName")
        val keyFile = cluster.getString("keyFile")

        import collection.JavaConverters._

        val images: List[RawImage] = cluster.getConfigList("images").asScala.map(RawImage(_))(collection.breakOut)
        val groups: List[RawGroup] = cluster.getConfigList("groups").asScala.map(RawGroup(_))(collection.breakOut)
        val postLaunchSteps: List[RawStep] = cluster.getConfigList("postLaunchSteps").asScala.map(RawStep(_))(collection.breakOut)

        RawConfiguration(
          name,
          region,
          keyName,
          keyFile,
          images,
          groups,
          postLaunchSteps)
      }
    }
  }

  case class ParseException(msg: String) extends Exception(msg)
}

case class RawImage(
  name: String,
  id: String,
  username: String)

object RawImage {
  def apply(config: Config): RawImage = {
    RawImage(
      config.getString("name"),
      config.getString("id"),
      config.getString("username"))
  }
}

case class RawGroup(
  name: String,
  image: String,
  instanceType: String,
  nbOfInstances: Int,
  spotPrice: Option[Double])

object RawGroup {
  def apply(config: Config): RawGroup = {
    RawGroup(
      config.getString("name"),
      config.getString("image"),
      config.getString("instanceType"),
      config.getInt("nbOfInstances"),
      if (config.hasPath("spotPrice")) {
        Some(config.getDouble("spotPrice"))
      } else {
        None
      })
  }
}

case class RawStep(
  group: String,
  cmd: String)

object RawStep {
  def apply(config: Config): RawStep = {
    RawStep(
      config.getString("group"),
      config.getString("cmd"))
  }
}