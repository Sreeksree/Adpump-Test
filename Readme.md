# AdPump

## Client

The client proxy sends the requests to the offshore proxy server.
The client proxy listens on the port 8081 for the requests from the browser.
The client uses a linked blocking queue to store the requests which helps in efficiently in producer and consumer scenarios.


To use the client proxy, run the Main file in the client package.


## Offshore Server.

Offshore proxy server accepts the requests that are forwarded from the client proxy and returns request ok as response.

The offshore proxy server accepts the requests on the port 8080.

To use the offshore proxy server run the Main in the server package.


## Configuring Proxy  in browser.

We could use the built-in proxy settings
Here I am using a browser extension called foxy poxy to proxy into the client.

configure the foxy proxy and enable it.

