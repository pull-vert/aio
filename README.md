# AIO
AIO is a reactive event-driven client/server IO library, supporting TCP, UDP, HTTP/2 and in the future QUIC and HTTP/3 protocols.

## Getting it
AIO require **JDK 9** or later, as it relies on j.u.c.Flow API and TLSv1.2. It is modularized with Jigsaw.

## Compile AIO
Clone AIO from [GitHub](https://github.com/pull-vert/aio).

## WIP
###aio-core
* Chan is an abstraction over java.nio.channels.SelectableChannel
* ChanTube act as a Publisher and Subscriber on a Chan
* SSL and TLSv1.2 support

###aio-tcp
* TcpServer
* TcpClient

## TODO
* Bybu, an abstraction over List<ByteBuffer>
* HTTP/2 module
* UDP module
* JDK 11 core module for TLSv1.3 support, and maybe more
* pure QUIC protocol module with TLSv1.3 ?
* [HTTP/3](https://quicwg.org/base-drafts/draft-ietf-quic-http.html) based on QUIC = UDP + TLSv1.3 or greater. It was previously known as HTTP-over-QUIC.
* Use [ServiceLoader](https://docs.oracle.com/javase/9/docs/api/java/util/ServiceLoader.html) mechanism for Executor to overwrite Executor used in JUnit tests.
* Kotlin first class support, with extension functions etc.

## License
AIO is Open Source Software released under the [GNU General Public License v2.0](https://www.gnu.org/licenses/gpl-2.0.html) and is subject to the [Classpath](https://www.gnu.org/software/classpath/license.html) exception.
