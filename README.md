# Best Energy Marketplace - Kotlin

Welcome to the Kotlin Best Energy Marketplace. This project is based on the
template https://github.com/corda/cordapp-template-kotlin.

# Pre-Requisites

See https://docs.corda.net/getting-set-up.html.

# Code of the Best Energy Marketplace

The code of the project can be found in the following directories:

* The state and contract definitions are found under `contracts/src/main/kotlin/`
* The flow definitions are found under `workflows/src/main/kotlin/`
* The client and webserver are found under `clients/src/main/kotlin/`

# Usage

## Running tests inside IntelliJ

We recommend editing your IntelliJ preferences so that you use the Gradle runner - this means that some flags are set
for you.

To switch to using the Gradle runner:

* Navigate to ``Build, Execution, Deployment -> Build Tools -> Gradle -> Runner`` (or search for `runner`)
    * Windows: this is in "Settings"
    * MacOS: this is in "Preferences"
* Set "Build and run using" to "Gradle"
* Set "Run test using" to "Gradle"

## Running the nodes

See https://docs.corda.net/tutorial-cordapp.html#running-the-example-cordapp.

Essentially you have to run the following commands in the console in the root directory of the project. The commands are
written for a Windows machine and might differ slightly for other operating systems.

First the nodes should be deployed locally. Which nodes are deployed can be followed in the build
script ``.\build.gradle``

    gradlew.bat deployNodes

After the completion of the previous task, the nodes can be launched locally.

     .\build\nodes\runnodes.bat

Now to access the nodes via a web interface a Spring Boot server for each party individually can be started. The Gradle
tasks can be found at the build script ``.\clients\build.gradle``. For example to start the server for Party A, we run
the following command.

    gradlew.bat runPartyAServer

Now the Spring Boot server for Party A is accessible via HTTP requests at ``localhost:50005``.

After starting the backend component we want to start the frontend component.

[comment]: <> (TODO How do we start the frontend component)

## Interacting with the nodes

### Shell

When started via the command line, each node will display an interactive shell:

    Welcome to the Corda interactive shell.
    Useful commands include 'help' to see what is available, and 'bye' to shut down the node.

    Tue Nov 06 11:58:13 GMT 2018>>>

You can use this shell to interact with your node. For example, enter `run networkMapSnapshot` to see a list of the
other nodes on the network:

    Tue Nov 06 11:58:13 GMT 2018>>> run networkMapSnapshot
    [
      {
      "addresses" : [ "localhost:10002" ],
      "legalIdentitiesAndCerts" : [ "O=Notary, L=London, C=GB" ],
      "platformVersion" : 3,
      "serial" : 1541505484825
    },
      {
      "addresses" : [ "localhost:10005" ],
      "legalIdentitiesAndCerts" : [ "O=PartyA, L=London, C=GB" ],
      "platformVersion" : 3,
      "serial" : 1541505382560
    },
      {
      "addresses" : [ "localhost:10008" ],
      "legalIdentitiesAndCerts" : [ "O=PartyB, L=New York, C=US" ],
      "platformVersion" : 3,
      "serial" : 1541505384742
    }
    ]

    Tue Nov 06 12:30:11 GMT 2018>>>

You can find out more about the node shell [here](https://docs.corda.net/shell.html).

### Web Interface

[comment]: <> (TODO How dow we access the web interface)