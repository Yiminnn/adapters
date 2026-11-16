import ipaddress
import socket
from urllib.parse import urljoin, urlsplit

import requests

_MAX_REDIRECTS = 5
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


def get_weather_from_provider(api_url: str) -> dict:
    """Fetch weather JSON from a provider URL, refusing internal/non-public targets.

    Every hop (including redirects, which are followed manually) is validated.
    # ponytail: validate-then-connect leaves a DNS-rebinding window; pin the resolved IP
    # via a custom transport adapter if that threat matters.
    """
    url = api_url
    for _ in range(_MAX_REDIRECTS + 1):
        _assert_public_url(url)
        response = requests.get(url, timeout=_TIMEOUT, allow_redirects=False)
        if response.is_redirect:
            url = urljoin(url, response.headers["Location"])
            continue
        response.raise_for_status()
        return response.json()
    raise ValueError("Too many redirects")
