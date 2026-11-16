import ipaddress
import re
import socket
import ssl
import urllib.error
import urllib.request
from urllib.parse import urlsplit

import certifi

_TIMEOUT = 10


def _assert_public_url(url: str) -> None:
    """Reject non-http(s) URLs and hosts that resolve to any non-public address."""
    parts = urlsplit(url)
    if parts.scheme not in ("http", "https"):
        raise ValueError(f"Unsupported URL scheme: {parts.scheme!r}")
    host = parts.hostname
    if not host:
        raise ValueError("URL has no host")
    port = parts.port or (443 if parts.scheme == "https" else 80)
    try:
        infos = socket.getaddrinfo(host, port, proto=socket.IPPROTO_TCP)
    except (socket.gaierror, UnicodeError) as e:
        raise ValueError(f"Cannot resolve host {host!r}") from e
    for info in infos:
        ip = ipaddress.ip_address(info[4][0].split("%", 1)[0])
        if ip.version == 6 and ip.ipv4_mapped:
            ip = ip.ipv4_mapped
        if not ip.is_global or ip.is_multicast:
            raise ValueError(f"Host {host!r} resolves to non-public address {ip}")


class _ValidatingRedirectHandler(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        _assert_public_url(newurl)
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def fetch_page_metadata(page_url: str) -> dict:
    """Fetch title/description of a public web page, refusing internal targets.

    Only http(s) is allowed, the host must resolve to public addresses only, and
    every redirect target is re-validated.
    # ponytail: validate-then-connect leaves a DNS-rebinding window; pin the resolved IP
    # in a custom HTTPConnection if that threat matters.
    """
    try:
        _assert_public_url(page_url)
        context = ssl.create_default_context(cafile=certifi.where())
        opener = urllib.request.build_opener(
            urllib.request.HTTPSHandler(context=context), _ValidatingRedirectHandler()
        )
        with opener.open(page_url, timeout=_TIMEOUT) as response:
            content = response.read().decode("utf-8", errors="replace")
    except ValueError:
        raise
    except (urllib.error.URLError, OSError, UnicodeDecodeError):
        return {"title": "Content unavailable", "description": "Unable to fetch page content"}

    title_match = re.search(r"<title>(.*?)</title>", content, re.IGNORECASE | re.DOTALL)
    title = title_match.group(1).strip() if title_match else "No title found"
    desc_match = re.search(
        r'<meta[^>]*name=["\']description["\'][^>]*content=["\'](.*?)["\']',
        content,
        re.IGNORECASE | re.DOTALL,
    )
    description = desc_match.group(1).strip() if desc_match else "No description available"
    return {"title": title, "description": description}
