
package org.skyluc.aws

import java.io.File
import scala.util.Failure
import scala.util.Success
import scala.util.Try
import org.skyluc.aws.conf.Action
import org.skyluc.aws.conf.Launch
import org.skyluc.aws.conf.RawConfiguration
import org.skyluc.aws.conf.RawConfiguration.ParseException
import com.typesafe.config.ConfigFactory
import org.skyluc.aws.conf.Configuration
import com.amazonaws.auth.profile.ProfileCredentialsProvider
import org.skyluc.aws.model.EC2Client

object ClusterCmd {

  private def usage(err: String, exitCode: Int, exception: Option[Throwable] = None): Nothing = {
    Console.err.println(err)
    Console.err.println("Usage: clustercmd <action> <cluster_conf>")
    exception.foreach {
      _.printStackTrace()
    }
    System.exit(exitCode)
    // never reached
    throw new Exception
  }

  def main(args: Array[String]): Unit = {
    
    for {
      parsedArgs <- parseArgs(args)
      action <- parseAction(parsedArgs._1)
      rawConfiguration <- RawConfiguration(parsedArgs._2)
    } yield (action, rawConfiguration)
    
    val (actionString, confPath) = handleFailure(parseArgs(args))
    
    // parse the action arguments
    val action = handleFailure(parseAction(actionString))
    
    // parse the content of the configuration file
    val rawConf = handleFailure(RawConfiguration(confPath))
    
    // extract the EC2 region
    val region = handleFailure(Configuration.regionFrom(rawConf))
    
    // load the user credentials
    val credentials = new ProfileCredentialsProvider().getCredentials()

    // create a client
    val client = EC2Client(credentials, region)
    
    // check the content of the configuration file
    val conf = handleFailure(Configuration(rawConf, client))
    
    println(conf)
    
    action.execute(conf, client)
  }
  
  private def handleFailure[A](res: Try[A]): A = {
    res match {
      case Success(res) =>
        res
      case Failure(e: ParseException) =>
        usage(e.getMessage, 2)
      case Failure(e) =>
        usage("error parsing configuration", 3, Some(e))
    }
  }
  
  private def parseArgs(args: Array[String]): Try[(String, String)] = {
      args.size match {
        case 0 =>
          Failure(ParseException("Missing arguments"))
        case 1 =>
          Failure(ParseException("Missing arguments"))
        case 2 =>
          Success((args(0), args(1)))
        case _ =>
          Failure(ParseException("Too many arguments"))
      }
  }

  private def parseAction(action: String) =
    Action(action).map{Success(_)}.getOrElse(Failure(ParseException(s"Unknown action: $action")))

}