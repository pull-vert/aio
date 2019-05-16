# AIO
AIO is a reactive event-driven client/server IO library written in Java, supporting File, TCP, UDP, HTTP/2 and in the future QUIC and HTTP/3 protocols.

AIO started as a fork of OpenJDK's HttpClient incubating since JDK9 and released with JDK11.

## Getting it
AIO require **JDK 9** or later, as it relies on j.u.c.Flow API and TLSv1.2. It is modularized with Jigsaw.

## Compile AIO
Clone AIO from [GitHub](https://github.com/pull-vert/aio).

## WIP
### aio-core
* Define Base API and abstract Classes for Selectable channels
* SSL and TLSv1.2 support

### aio-tcp
* TcpServer
* TcpClient

## TODO
* Bybu, an abstraction over a single ```ByteBuffer``` or ```List<ByteBuffer>```
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

## License
AIO is Open Source Software released under the [GNU General Public License v2.0](https://www.gnu.org/licenses/gpl-2.0.html) and is subject to the [Classpath](https://www.gnu.org/software/classpath/license.html) exception.

This Classpath Exception on GPL2 allow you to include a dependency on AIO in your library, regardless of the license terms of it.
