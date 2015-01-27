package org.skyluc.aws.model

import com.amazonaws.auth.AWSCredentials
import com.amazonaws.regions.Regions
import com.amazonaws.services.ec2.AmazonEC2Client
import scala.concurrent.Future
import com.amazonaws.services.ec2.model.DescribeSecurityGroupsRequest
import com.amazonaws.services.ec2.model.CreateSecurityGroupRequest
import com.amazonaws.services.ec2.model.RequestSpotInstancesRequest
import com.amazonaws.services.ec2.model.LaunchSpecification
import com.amazonaws.services.ec2.model.InstanceType
import org.skyluc.aws.utils.Watcher
import org.skyluc.aws.utils.WatchItem
import scala.concurrent.Promise
import com.amazonaws.services.ec2.model.DescribeSpotInstanceRequestsRequest
import com.amazonaws.services.ec2.model.Filter
import com.amazonaws.services.ec2.model.DescribeInstancesRequest
import com.amazonaws.services.ec2.model.DescribeImagesRequest
import com.amazonaws.services.ec2.model.DescribeKeyPairsRequest
import com.amazonaws.services.ec2.model.CreateTagsRequest
import com.amazonaws.services.ec2.model.Tag

case class EC2Client private (private[model]client: AmazonEC2Client) {

  def findSecurityGroup(name: String): Option[SecurityGroup] = {
    val request = new DescribeSecurityGroupsRequest()
    val result = client.describeSecurityGroups(request)
    import collection.JavaConverters._
    result.getSecurityGroups.asScala.find(_.getGroupName == name).map(new SecurityGroup(_, this))
  }

  private[model] def findSecurityGroupWithId(id: String): Option[SecurityGroup] = {
    val request = new DescribeSecurityGroupsRequest().withGroupIds(id)
    val result = client.describeSecurityGroups(request)
    val list = result.getSecurityGroups
    if (list.size() > 0) {
      Some(new SecurityGroup(list.get(0), this))
    } else {
      None
    }
  }

  def createSecurityGroup(name: String, description: String): SecurityGroup = {
    val request = new CreateSecurityGroupRequest().withGroupName(name).withDescription(description)
    val result = client.createSecurityGroup(request)
    val groupId = result.getGroupId
    findSecurityGroupWithId(groupId) match {
      case Some(sc) =>
        sc
      case None =>
        throw new Exception(s"Failed to find created security group $name")
    }
  }

  def requestSpotInstances(/*region: Regions,*/ nbOfNodes: Int, imageId: String, securityGroup: SecurityGroup, keyName: String, spotPrice: Double): Future[List[String]] = {
    val launchSpecification = new LaunchSpecification()
      .withSecurityGroups(securityGroup.groupName)
      .withImageId(imageId)
      .withInstanceType(InstanceType.M3Large)
      .withKeyName(keyName)

    val request = new RequestSpotInstancesRequest()
      .withInstanceCount(nbOfNodes)
      .withSpotPrice(spotPrice.toString()).withLaunchSpecification(launchSpecification)

    val result = client.requestSpotInstances(request)

    val instanceRequests = result.getSpotInstanceRequests
    import collection.JavaConverters._
    val spotRequestIds: List[String] = instanceRequests.asScala.map(_.getSpotInstanceRequestId)(collection.breakOut)

    val promise = Promise[List[String]]

    Watcher.toWatch(WatchItem(2, 60) { () =>
      val statuses: List[String] = getSpotRequestStatuses(spotRequestIds)
      println(s"statuses: $statuses")
      statuses.forall { _ == "active" }
    } { () =>
      println("sleep 2")
      println("complete promise")
      promise.success(getSpotRequestInstanceIds(spotRequestIds))
    })

    promise.future
  }

  /** Returns the instance with the given ids. Waits until the instances are in a 'good' state (public ip, ...)
   */
  def findStartedInstances(instanceIds: List[String]): Future[List[Instance]] = {

    val promise = Promise[List[Instance]]

    Watcher.toWatch(WatchItem(2, 60) { () =>
      val instances = getInstances(instanceIds)
      println(s"instances: ${instances.map(_.publicIp)}")
      instances.size == instanceIds.size && instances.forall(_.isReady)
    } { () =>
      println("complete promise 2")
      promise.success(getInstances(instanceIds))
    })
    val result = client.describeInstances()

    promise.future
  }

  private def getInstances(instanceIds: List[String]): List[Instance] = {
    val result = client.describeInstances()

    import collection.JavaConverters._
    result.getReservations.asScala
      .flatMap(_.getInstances.asScala)
      .filter { i => instanceIds.contains(i.getInstanceId) }
      .map(Instance(_, this))(collection.breakOut)

  }

  private def getSpotRequestStatuses(spotRequestIds: List[String]): List[String] = {
    val request = new DescribeSpotInstanceRequestsRequest()
    import collection.JavaConverters._
    request.withSpotInstanceRequestIds(spotRequestIds.asJava)

    val result = client.describeSpotInstanceRequests(request)
    result.getSpotInstanceRequests.asScala.map(_.getState)(collection.breakOut)
  }

  private def getSpotRequestInstanceIds(spotRequestIds: List[String]): List[String] = {
    val request = new DescribeSpotInstanceRequestsRequest()
    import collection.JavaConverters._
    request.withSpotInstanceRequestIds(spotRequestIds.asJava)

    val result = client.describeSpotInstanceRequests(request)
    result.getSpotInstanceRequests.asScala.map(_.getInstanceId)(collection.breakOut)
  }

  def imageWithId(imageId: String): Option[Image] = {
    val request = new DescribeImagesRequest().withFilters(new Filter().withName("image-id").withValues(imageId))
    val result = client.describeImages(request)
    val images = result.getImages
    if (images.size() == 1) {
      return Some(Image(images.get(0)))
    } else {
      None
    }
  }

  def keyWithName(keyName: String): Option[KeyPair] = {
    val request = new DescribeKeyPairsRequest().withFilters(new Filter().withName("key-name").withValues(keyName))
    val result = client.describeKeyPairs(request)
    val keyPairs = result.getKeyPairs
    if (keyPairs.size() == 1) {
      return Some(KeyPair(keyPairs.get(0)))
    } else {
      None
    }
  }

  def setTag(key: String, value: String, resourceId: String) {
    val request = new CreateTagsRequest().withTags(new Tag().withKey(key).withValue(value)).withResources(resourceId)
    client.createTags(request)
  }

}

object EC2Client {

  def apply(credendials: AWSCredentials, region: Regions): EC2Client = {
    val client: AmazonEC2Client = new AmazonEC2Client(credendials).withRegion(region)
    EC2Client(client)
  }

}