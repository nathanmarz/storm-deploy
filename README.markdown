## Getting Started

`storm-deploy` makes it dead-simple to launch Storm clusters on AWS. It is built on top of [jclouds](http://www.jclouds.org/) and [pallet](https://github.com/pallet/pallet). After you follow the instructions in this tutorial, you will be able to provision, configure, and install a fully functional Storm cluster with just one command:

```
lein deploy-storm --start --name mycluster
```

You can then stop a cluster like this:

```
lein deploy-storm --stop --name mycluster
```

The deploy also installs [Ganglia](http://ganglia.sourceforge.net/) which provides fine-grained metrics about resource usage on the cluster. If you supply a NewRelic key, it will send performance metrics to [NewRelic](http://www.newrelic.com). If you supply a Splunk credentials.spl file, it will send logs to Splunk Storm.

## Fork

This project is a fork of the original storm-deploy that adds a number of features. We made the decision to fork rather than contribute because we wanted to make significant changes to the way storm-deploy works. You can check out the [original project](http://github.com/nathanmarz/storm-deploy), also here on GitHub.

## Branches

The 'development' branch contains the latest version of the code we are working on that is probably stable, but hasn't been extensively tried out. Think of it as the beta branch. The 'master' branch always contains what we believe to be a stable version of the code.

## Help

If you run into any issues, the best place to ask for help is [mailing list](http://groups.google.com/group/storm-user). Be sure to indicate your are using the adsummos fork of storm-deploy and not the original. Otherwise you may get confusing advice.

## Instructions

### Setup

1) Install [leiningen -  version 2 only](https://github.com/technomancy/leiningen). All you have to do is download [this script](https://raw.github.com/technomancy/leiningen/stable/bin/lein), place it on your PATH, and make it executable.

2) Clone `storm-deploy` using git (`git clone https://github.com/adsummos/storm-deploy.git`)

3) Run `lein deps`

4) Create a `~/.pallet/config.clj` file that looks like the following (and fill in the blanks). This provides the deploy with the credentials necessary to launch and configure instances on AWS.

```clojure
(defpallet
  :services
  {
   :default {
             :blobstore-provider "aws-s3"
             :provider "aws-ec2"
             :environment {:user {:username "storm"  ; this must be "storm"
                                  :private-key-path "$YOUR_PRIVATE_KEY_PATH$"
                                  :public-key-path "$YOUR_PUBLIC_KEY_PATH$"}
                           :aws-user-id "$YOUR_USER_ID$"}
             :identity "$YOUR_AWS_ACCESS_KEY$"
             :credential "$YOUR_AWS_ACCESS_KEY_SECRET$"
             :jclouds.regions "$YOUR_AWS_REGION$"
             }
    })
```

The deploy needs:

1. Public and private key paths for setting up ssh on the nodes. The public key path must be the private key path + ".pub" (this seems to be a bug in pallet). On Linux, you should have a null passphrase on the keys.

   a. If you are running a ssh agent (e.g. you are on Mac OS X), then you must ensure that your key is available to the agent. You can make this change permanently using:

   ```
   ssh-add -K $YOUR_PRIVATE_KEY_PATH$
   ```

2. AWS user id: You can find this on your account management page. It's a numeric number with hyphens in it. Optionally take out the hyphens when you put it in the config.

3. Identity: Your AWS access key

4. Credential: Your AWS access key secret

### Configs

You can create configs for each cluster you run so you don't have to edit the defaut configs all the time. In the storm-deploy project, there is a `conf` directory that contains `default` and a few other subdirectories of example cluster configs. When you specify a name for your cluster, like 'mycluster', storm-deploy will look for config files in `conf/mycluster/`. If it doesn't find one or all of them, it looks for them in the `conf/default` directory.

You can also specify a conf directory in a custom location (note that this must also have a `default` subdirectory) using the `--confdir` commandline option.

```
lein deploy-storm --start --confdir ~/myconfs --name mycluster
```

### Launching clusters

Run this command:

`lein deploy-storm --start --name mycluster`

The `--name` parameter names your cluster so that you can attach to it or stop it later. If you omit `--name`, it will default to "dev".

The deploy sets up Zookeeper, sets up Nimbus, launches the Storm UI on port 8080 on Nimbus, launches a DRPC server on port 3772 on Nimbus, sets up the Supervisors, sets configurations appropriately, sets the appropriate permissions for the security groups, and _attaches_ your machine to the cluster (see below for more information on attaching). 

If you are familiar with the original storm-deploy, note that there is no `--release` parameter to indicate which release of Storm to install. This is because the parameter is not very useful because, as currently designed, storm-deploy will only really work with one version of storm at a time. Currently the release version is fixed at 0.8.3.


### Stopping clusters

Simply run:

`lein deploy-storm --stop --name mycluster`

This will shut down Nimbus, the Supervisors, and the Zookeeper nodes.

### Attaching to a cluster

Attaching to a cluster configures your `storm` client to talk to that particular cluster as well as giving your computer authorization to view the Storm UI. The `storm` client is used to start and stop topologies and is described [here](https://github.com/nathanmarz/storm/wiki/Setting-up-development-environment). 

To attach to a cluster, run the following command:

`lein deploy-storm --attach --name mycluster`

Attaching does the following:

1. Writes the location of Nimbus in `~/.storm/storm.yaml` so that the `storm` client knows which cluster to talk to
2. Authorizes your computer to access the Nimbus daemon's Thrift port (which is used for submitting topologies)
3. Authorizes your computer to access the Storm UI on port 8080 on Nimbus
4. Authorizes your computer to access Ganglia on port 80 on Nimbus


### Getting IPs of cluster nodes

To get the IP addresses of the cluster nodes, run the following:

`lein deploy-storm --ips --name mycluster`

### Ganglia

You can access Ganglia by navigating to the following address on your web browser:

`http://{nimbus ip}/ganglia/index.php`

### NewRelic

In `clusters.yml`, you can set `newrelic.licensekey` to your newrelic API license key and this will cause performance metrics to be sent to NewRelic about the storm servers. If you leave it blank or anything invalid, it will just silently not work.

### Splunk Storm

If you add your Splunk Storm `credentials.spl` file to the root of your `conf` directory it will install the Splunk universal forwarder and send all storm logs to Splunk Storm.

## Acknowledgement

Thanks to [Korrelate](http://korrelate.com), my ([gworley3](https://github.com/gworley3)) employer. You can learn more about what we're doing from our [engineering blog](http://engineering.korrelate.com/).
