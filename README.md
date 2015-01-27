# aws-cluster-cmd

`aws-cluster-cmd` is a Scala tool for deploying and managing clusters on AWS EC2, based on a cluster definition contained in a [Typesafe config](https://github.com/typesafehub/config) file.

## Features

In version 0.0.1, `aws-cluster-cmd` supports deploying a cluster, according to the options contained in the config file. The main configuration points are the AMIs
 to use, the group of instances to start and steps to perform post launch.

The current limitation are:

* The rules of the security group are hardcoded, to support a Mesos/HDFS/Spark cluster.
* It only supports starting spot instances.

# Building

To build the tool, clone the git repo, and run `sbt universal:packageZipTarball` from the project folder.

This generates the archive file at `target/universal/aws-cluster-cmd-0.0.1.tgz`.

# Installing

To install the tool, decompress the tar.gz file somewhere:

```bash
tar xf aws-cluster-cmd-0.0.1.tgz
```

# Usage

To launch the tool, use:

```
bin/aws-cluster-cmd <action> <config_file>
```

The only supported action is `launch`, which launch a cluster according to the content of the config file.

## config file

`aws-cluster-cmd` extracts all information it requires to launch a cluster from the config file

It uses the [Typesafe config](https://github.com/typesafehub/config) format, and has the following content:


```
cluster { 
  name = "cluster-name"                            # the name of the cluster. Will be used to name the instances
  region = "us-east-1"                             # the EC2 region where to launch the cluster
  keyName = "name-of-your-key-pair",               # the name of the key pair in EC2
  keyFile = "/path/to/your/private/key"            # file containing the private key of the key pair

  images = [                                       # the definition of the images used for the instances

    { name = "my-image"                            # the name of the image (used as id in the other parts of the config)
      id = "ami-3c3a4254"                          # the id of the image in EC2
      username = "ubuntu" }                        # the username to use to connect to the instance

    # ...
  ]

  groups = [                                       # the group of instances to start

    { name = "master"                              # the name of the group (used as id in the other part of the config)
      image = "my-image"                           # the image to use for these instances
      instanceType = "m3.large"                    # the instance type to start
      nbOfInstances = 1                            # the number of instances of this group to start
      spotPrice = 0.017                            # the spot price to request 
    }

    { name = "node"
      image = "my-image"
      instanceType = "m3.large"
      nbOfInstances = 2
      spotPrice = 0.017
    }

    # ...
  ]

  postLaunchSteps = [                              # the commands to execute on the instances after they are up and running
                                                   # command to run on each instance of the master group
    { group = "master", cmd = "~/some/master/script" }
                                                   # command to run on each instance of the node group (with a variable)
    { group = "node", cmd = "~/some/node/script ${master/0/privateIp}" }
  ]
}
```

## variables

It is possible to use variables in the command of the post-launch steps. They have the format:   `${<group_name>/<instance_number>/<attribute>}`.

Currently, only the attribute `privateIp` is supported. It is substituted with the private id of the requested instance from the given group.


