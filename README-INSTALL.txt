# Copyright 2019 Hewlett Packard Enterprise Development LP

SAP HANA Backup Agent - INSTALL
-------------------------------

To install the SAP-HANA backup agent, perform the following steps:

01) Unzip the deliverable file to your run location on the host machine. This
    will be referred to as the AGENT-DIR in subsequent steps.

02) Edit the sap-hana-backup-agent-config.xml file in:
        /AGENT-DIR/bin/
    and enter the correct configuration information.

03) Locate the ngdbc.jar file on the SAP HANA host. Use the following command:
        > find / -name ngdbc.jar 2> /dev/null

04) Verify that you have a .bashrc file in your home directory. If not, create
    one and add the following line:
        #!/bin/bash

05) Set the SAP_JDBC_DRIVER environment variable to point to the ngdbc.jar file.
    The easiest way to do this is to modify the .bashrc file by adding the
    following line:
        export SAP_JDBC_DRIVER=/[PATH]/[TO]/ngdbc.jar
    This will ensure that the environment variable for the backup agent user is
    set at login.

06) To trigger the .bashrc to run do:
    > source .bashrc

07) To launch the backup agent:
    a)  cd /AGENT-DIR/bin/
    b)  ./nimble-sap-agent
