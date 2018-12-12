# AIO
AIO is a reactive j.u.c.Flow event-driven IO library, supporting TCP, UDP, HTTP/2, HTTP/3 protocols.

AIO require **JDK 9** or later, as it relies on j.u.c.Flow API and TLSv1.2. It is modularized with Jigsaw.

## Compile AIO
Clone AIO from [GitHub](https://github.com/pull-vert/aio).

## WIP
aio-core :
* Chan is an abstraction over java.nio.channels.SelectableChannel
* ChanTube is a Publisher and Subscriber on a Chan
* SSL and TLSv1.2 support

aio-tcp :
* TcpServer
* TcpClient

## TODO
* Bybu, an abstraction over List<ByteBuffer>
* HTTP-2 module
* UDP module
* JDK 11 core module with at least TLSv1.3 support
* pure QUIC support
* [HTTP/3](https://quicwg.org/base-drafts/draft-ietf-quic-http.html) based on QUIC = UDP + TLSv1.3 or greater. It was previously known as HTTP-over-QUIC.
