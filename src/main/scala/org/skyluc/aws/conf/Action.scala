package org.skyluc.aws.conf

import org.skyluc.aws.model.EC2Client
import org.skyluc.aws.model.IPBasedIPRule
import org.skyluc.aws.model.GroupBasedIPRule
import org.skyluc.aws.model.Instance
import scala.concurrent.Future
import org.skyluc.aws.model.SecurityGroup

import scala.concurrent.ExecutionContext.Implicits.global

abstract class Action private[conf] (val keywork: String) {
  def execute(conf: Configuration, client: EC2Client)
}

object Action {

  final val SecurityGroupSuffix = "-security-group"

  val StupideVarRegex = "\\$\\{[^\\{\\}]*\\}".r

  private val list = List(Launch)
  def apply(keywork: String): Option[Action] = {
    list.find { _.keywork == keywork }
  }
}

object Launch extends Action("launch") {

  def execute(conf: Configuration, client: EC2Client) {

    // TODO: make security group configurable
    val securityGroupName = s"${conf.name}${Action.SecurityGroupSuffix}"
    val securityGroupOpt = client.findSecurityGroup(securityGroupName)

    val initialSecurityGroup = securityGroupOpt match {
      case Some(sc) =>
        sc
      case None =>
        client.createSecurityGroup(securityGroupName, s"Security group for the ${conf.name} cluster")
    }

    // set the rules in the security group
    val securityGroup = initialSecurityGroup.setInboundRules(List(
      GroupBasedIPRule(initialSecurityGroup.groupId, "tcp", 0, 65535),
      IPBasedIPRule("0.0.0.0/0", "tcp", 22, 22),
      IPBasedIPRule("0.0.0.0/0", "tcp", 4040, 4040),
      IPBasedIPRule("0.0.0.0/0", "tcp", 5050, 5051),
      IPBasedIPRule("0.0.0.0/0", "tcp", 50070, 50070),
      IPBasedIPRule("0.0.0.0/0", "tcp", 50075, 50075)))

    val groups: Future[Map[String, GroupWithInstances]] = Future.sequence(conf.groups.map { g =>
      startGroupInstances(g, conf, securityGroup, client)
    })
      .map { l =>
        l.map { g =>
          (g.group.name, g)
        }(collection.breakOut)
      }

    groups.map { gs =>
      executePostLaunchSteps(conf.postLaunchSteps, gs, conf)
    }.foreach { x => System.exit(0) }

  }

  private def startGroupInstances(group: Group, conf: Configuration, securityGroup: SecurityGroup, client: EC2Client): Future[GroupWithInstances] = {
    // TODO: support non-spot instances
    val instanceIds = client.requestSpotInstances(group.nbOfInstances, group.image.id, securityGroup, conf.keyName, group.spotPrice.get)

    val instances = instanceIds.flatMap { is =>
      client.findStartedInstances(is)
    }

    instances.map{ l =>
      l.zip(1 to group.nbOfInstances).foreach { p =>
        p._1.setTag("Name", s"${conf.name}-${group.name}-${p._2}")
        p._1.setTag("cluster-id", conf.name)
        p._1.setTag("cluster-group", group.name)
      }
      l
    }.map { is =>
      GroupWithInstances(group, is)
    }
  }

  private def executePostLaunchSteps(steps: List[Step], groups: Map[String, GroupWithInstances], conf: Configuration) {
    for {
      step <- steps
      group = groups(step.group.name)
      instance <- group.instances
    } {
      instance.executeSshCommand(group.group.image.username, conf.keyFile, step.resolveCmdWith(groups))
    }
  }

  case class GroupWithInstances(
    group: Group,
    instances: List[Instance])
}