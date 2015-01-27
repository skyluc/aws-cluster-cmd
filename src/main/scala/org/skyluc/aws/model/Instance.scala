package org.skyluc.aws.model

import com.amazonaws.services.ec2.model.{ Instance => AInstance }
import java.io.File
import com.amazonaws.services.ec2.model.CreateTagsRequest
import com.amazonaws.services.ec2.model.Tag

case class Instance(instance: AInstance, client: EC2Client) {

  def publicIp: String = instance.getPublicIpAddress
  
  def privateIp: String = instance.getPrivateIpAddress

  def isReady: Boolean = {
    publicIp != null
  }

  def executeSshCommand(username: String, keyFile: File, cmd: String) {

    import scala.sys.process._

    val output = Process(
      Seq(
        "ssh",
        "-o", "StrictHostKeyChecking=no",
        "-i", keyFile.getAbsolutePath,
        s"$username@$publicIp",
        cmd)) ! ProcessLogger(o => println(o), e => Console.err.println(e))
  }
  
  def setTag(key: String, value: String) {
    client.setTag(key, value, instance.getInstanceId)
  }

}

abstract class InstanceField private[model] (val keywork: String) {
  def valueFrom(instance: Instance): String
}

object InstanceField {
  
  private val list = List(PrivateIp)
  def apply(keywork: String): Option[InstanceField] = {
    list.find { _.keywork == keywork }
  }

  object PrivateIp extends InstanceField("privateIp") {
    def valueFrom(instance: Instance): String = {
      instance.privateIp
    }
  }
}
