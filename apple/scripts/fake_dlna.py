"""Server DLNA minimo per le schermate nel simulatore: una cartella "Festa" con le foto demo.

Uso: python3 scripts/fake_dlna.py <indirizzo> <cartella con le foto>
Risponde sulla porta 8200 come MiniDLNA (/rootDesc.xml e Browse su /ctl/ContentDir).
"""
import html
import socketserver
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

HOST = sys.argv[1]
PHOTOS = sorted(Path(sys.argv[2]).glob("*.jpg"))
BASE = f"http://{HOST}:8200"

DESCRIPTION = f"""<?xml version="1.0"?>
<root xmlns="urn:schemas-upnp-org:device-1-0"><device>
<deviceType>urn:schemas-upnp-org:device:MediaServer:1</deviceType>
<friendlyName>NAS di casa</friendlyName><UDN>uuid:fotofesta-nas-di-prova</UDN>
<serviceList><service><serviceType>urn:schemas-upnp-org:service:ContentDirectory:1</serviceType>
<controlURL>/ctl/ContentDir</controlURL></service></serviceList></device></root>"""


def didl(content):
    return ('<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" '
            f'xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">{content}</DIDL-Lite>')


def browse(object_id):
    if object_id == "0":
        items = ['<container id="1" parentID="0"><dc:title>Festa di compleanno</dc:title>'
                 '<upnp:class>object.container.storageFolder</upnp:class></container>',
                 '<container id="2" parentID="0"><dc:title>Musica</dc:title>'
                 '<upnp:class>object.container.album.musicAlbum</upnp:class></container>']
    elif object_id == "1":
        items = [f'<item id="p{i}" parentID="1"><dc:title>{photo.name}</dc:title>'
                 '<upnp:class>object.item.imageItem.photo</upnp:class>'
                 f'<res protocolInfo="http-get:*:image/jpeg:*">{BASE}/media/{photo.name}</res></item>'
                 for i, photo in enumerate(PHOTOS)]
    else:
        items = []
    body = html.escape(didl("".join(items)), quote=False)
    return ('<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body>'
            '<u:BrowseResponse xmlns:u="urn:schemas-upnp-org:service:ContentDirectory:1">'
            f'<Result>{body}</Result><NumberReturned>{len(items)}</NumberReturned>'
            f'<TotalMatches>{len(items)}</TotalMatches><UpdateID>1</UpdateID>'
            '</u:BrowseResponse></s:Body></s:Envelope>')


class Handler(BaseHTTPRequestHandler):
    def log_message(self, format, *args):
        print(self.address_string(), format % args, flush=True)

    def reply(self, status, body, kind):
        self.send_response(status)
        self.send_header("Content-Type", kind)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == "/rootDesc.xml":
            self.reply(200, DESCRIPTION.encode(), "text/xml")
        elif self.path.startswith("/media/") and (Path(sys.argv[2]) / Path(self.path).name).exists():
            self.reply(200, (Path(sys.argv[2]) / Path(self.path).name).read_bytes(), "image/jpeg")
        else:
            self.reply(404, b"", "text/plain")

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        request = self.rfile.read(length).decode("utf-8", "replace")
        object_id = request.split("<ObjectID>")[1].split("</ObjectID>")[0] if "<ObjectID>" in request else "0"
        print("Browse", object_id, flush=True)
        self.reply(200, browse(object_id).encode(), 'text/xml; charset="utf-8"')


class Server(ThreadingHTTPServer):
    def server_bind(self):
        # HTTPServer cerca il nome del computer con il DNS inverso, che sul runner può bloccarsi
        # prima che il server si metta in ascolto.
        socketserver.TCPServer.server_bind(self)
        self.server_name, self.server_port = self.server_address[:2]


server = Server((HOST, 8200), Handler)
print("Server DLNA finto pronto su", HOST, flush=True)
server.serve_forever()
