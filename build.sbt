name := "aws-cluster-cmd"

version := "0.0.1"

libraryDependencies ++= Seq(
  "com.amazonaws" % "aws-java-sdk-ec2" % "1.9.11",
  "com.typesafe" % "config" % "1.2.1"
) 

enablePlugins(SbtNativePackager, UniversalPlugin, JavaAppPackaging)

// adds 'sample-conf' folder content to the dist

mappings in Universal ++= IO.listFiles(file("sample-conf")).map(f => f -> s"sample-conf/${f.getName()}")(collection.breakOut)

