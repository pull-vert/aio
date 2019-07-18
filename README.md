# AIO
AIO is a reactive event-driven client/server IO library written in Java, supporting TCP and in the future File, UDP, HTTP/2, QUIC and HTTP/3 protocols.

AIO started as a fork of OpenJDK's HttpClient incubating since JDK9 and released with JDK11.

## Getting it
AIO require LTS **JDK 11** or later, as it relies on j.u.c.Flow API and TLSv1.3. It is modularized with Jigsaw.

## Compile AIO
Clone AIO from [GitHub](https://github.com/pull-vert/aio).

## WIP
### aio-core
* Define Base API and abstract Classes for Selectable channels
* Bybu, an abstraction over a single ```ByteBuffer``` or ```List<ByteBuffer>```
* SSL and TLSv1.3 (and below) support

### aio-tcp
* TcpServer
* TcpClient

## TODO
* File module
* HTTP/2 module
* UDP module
* JDK 11 core module for TLSv1.3 support, and maybe more
* base QUIC protocol module with TLSv1.3 ?
* [HTTP/3](https://quicwg.org/base-drafts/draft-ietf-quic-http.html) based on QUIC = UDP + TLSv1.3 or greater. Previously known as HTTP-over-QUIC.
* Kotlin first class support, with extension functions etc.

## Ideas (maybe not that good)
* Use [ServiceLoader](https://docs.oracle.com/javase/9/docs/api/java/util/ServiceLoader.html) mechanism
  * Server and Clients can have different implementation for JDK9 (aio-*) and JDK11 (aio-jdk11-*) 
  * Executor used in tests can be different from normal Executor
* Use [JEP238 multi-release jar](http://openjdk.java.net/jeps/238), for exemple for TLSv1.3

## Inspirations, nice articles
* Netty
* Aeron
* [reactive IO and backpressure - akka](https://medium.com/@unmeshvjoshi/understanding-reactive-io-and-back-pressure-with-your-own-akka-http-server-d4b64921059a)
* [IO Reactor pattern](https://github.com/iluwatar/java-design-patterns/tree/master/reactor)
* [RSocket](http://rsocket.io/docs/FAQ)

## License
AIO is Open Source Software released under the [GNU General Public License v2.0](https://www.gnu.org/licenses/gpl-2.0.html) and is subject to the [Classpath Exception](https://www.gnu.org/software/classpath/license.html).

This Classpath Exception of GPL2 allow you to include a dependency on AIO in your library, regardless of your library's license.
