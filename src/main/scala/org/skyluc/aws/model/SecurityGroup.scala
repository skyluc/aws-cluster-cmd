package org.skyluc.aws.model

import com.amazonaws.services.ec2.model.{ SecurityGroup => ASecurityGroup }
import com.amazonaws.services.ec2.AmazonEC2Client
import com.amazonaws.services.ec2.model.AuthorizeSecurityGroupIngressRequest
import com.amazonaws.services.ec2.model.IpPermission
import com.amazonaws.services.ec2.model.UserIdGroupPair

class SecurityGroup private[model] (sc: ASecurityGroup, client: EC2Client) {

  def groupId = sc.getGroupId

  def groupName = sc.getGroupName

  def setInboundRules(rules: List[IPRule]): SecurityGroup = {

    // get the current permissions and compute the permissions to add
    import collection.JavaConverters._
    val existing = sc.getIpPermissions.asScala.flatMap(IPRule(_))
    val toAdd = rules.diff(existing)

    if (!toAdd.isEmpty) {
      val request = new AuthorizeSecurityGroupIngressRequest()

      request.withGroupId(sc.getGroupId).withIpPermissions(toAdd.map(_.ipPermission).asJava)
      client.client.authorizeSecurityGroupIngress(request)
      client.findSecurityGroupWithId(sc.getGroupId).get
    } else {
      this
    }
  }

}

sealed trait IPRule {
  val protocol: String
  val fromPort: Int
  val toPort: Int

  def ipPermission: IpPermission
}

object IPRule {
  def apply(ipPermission: IpPermission): List[IPRule] = {
    import collection.JavaConverters._
    val ipRanges = ipPermission.getIpRanges
    if (ipRanges.isEmpty()) {
      // TODO: this does not support groups defined on other accounts
      val groupIds = ipPermission.getUserIdGroupPairs
      groupIds.asScala.map(ug =>
        GroupBasedIPRule(ug.getGroupId, ipPermission.getIpProtocol, ipPermission.getFromPort, ipPermission.getToPort))(collection.breakOut)
    } else {
      ipRanges.asScala.map(r =>
        IPBasedIPRule(r, ipPermission.getIpProtocol, ipPermission.getFromPort, ipPermission.getToPort))(collection.breakOut)
    }
  }
}

case class IPBasedIPRule(
  val ipRange: String,
  val protocol: String,
  val fromPort: Int,
  val toPort: Int) extends IPRule {

  def ipPermission: IpPermission =
    new IpPermission()
      .withIpRanges(ipRange)
      .withIpProtocol(protocol)
      .withFromPort(fromPort)
      .withToPort(toPort)
}

case class GroupBasedIPRule(
  val groupId: String,
  val protocol: String,
  val fromPort: Int,
  val toPort: Int) extends IPRule {

  def ipPermission: IpPermission =
    new IpPermission()
      .withUserIdGroupPairs(new UserIdGroupPair().withGroupId(groupId))
      .withIpProtocol(protocol)
      .withFromPort(fromPort)
      .withToPort(toPort)
}