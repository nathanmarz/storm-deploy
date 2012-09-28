## Getting Started

This project makes it dead-simple to deploy Storm clusters on AWS. See [the wiki](https://github.com/nathanmarz/storm-deploy/wiki) for instructions on using this deploy.

##
AWS regional deploy.

Make sure your ~/.pallet/config.clj contains (example for eu-west-1):

             :jclouds.regions "eu-west-1"
	     :jclouds.ec2.cc-regions "eu-west-1"

Make sure you find appropriate images available in your region and place them in conf/clusters.yaml (example for eu-west-1)

nimbus.image: "eu-west-1/ami-1a0f3d6e"         #64-bit ubuntu
nimbus.hardware: "m1.large"

supervisor.count: 1
supervisor.image: "eu-west-1/ami-1a0f3d6e"         #64-bit ubuntu on eu-west-1
supervisor.hardware: "m1.large"
#supervisor.spot.price: 1.60


zookeeper.count: 1
zookeeper.image: "eu-west-1/ami-1a0f3d6e"         #64-bit ubuntu
zookeeper.hardware: "m1.large"


## Acknowledgements

YourKit is kindly supporting open source projects with its full-featured Java Profiler. YourKit, LLC is the creator of innovative and intelligent tools for profiling Java and .NET applications. Take a look at YourKit's leading software products: [YourKit Java Profiler](http://www.yourkit.com/java/profiler/index.jsp) and [YourKit .NET Profiler](http://www.yourkit.com/.net/profiler/index.jsp).

