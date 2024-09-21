# HiWi

To install floodlight: Clone the main branch from https://github.com/floodlight/floodlight.git
In .../floodlight/lib delete the netty and thrift jars. Download the netty 4.1.66 and libthirft 0.14.1 jars to replace them.
Make sure to have Java JDK 8 as the active Java JDK.

-----------------------------------------------------------------------
Usage for monitoring:

To use the controller for monitoring first start the python classifierServer.py app. The app can be found in the directory /floodlight/src/main/python/classifier. Remember to set the classifier's CNN resolution to the one of the experiments. 

After starting the python app go to the /floodlight directory and execute: sudo java -jar target/floodlight.jar

-----------------------------------------------------------------------
Traffic generation:

TRex: To generate traffic with TRex you have to start TRex in interactive mode in the traffic generation server with
cd /opt/trex/v3.04/
sudo ./t-rex-64 -i

Afterwards start the corresponding python server

Remember to bind the interfaces with dpdk-devbind to the vfio-pci driver beforehand

Tcpreplay:


Remember to bind the interfaces with dpdk-devbind to the i40e driver beforehand. Put the interfaces up after that.