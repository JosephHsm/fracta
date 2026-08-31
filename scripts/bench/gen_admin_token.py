"""벤치용 ADMIN JWT 1개를 출력한다 (배정 확정 API 호출용)."""
import base64
import hashlib
import hmac
import json
import sys
import time

SECRET = b"fracta-local-dev-jwt-secret-key-0123456789"


def b64url(data: bytes) -> bytes:
    return base64.urlsafe_b64encode(data).rstrip(b"=")


def main() -> None:
    subject = sys.argv[1] if len(sys.argv) > 1 else "999999"
    now = int(time.time())
    header = b64url(json.dumps({"alg": "HS256", "typ": "JWT"}).encode())
    payload = b64url(json.dumps({
        "sub": subject,
        "iat": now,
        "exp": now + 43_200,
        "name": "bench-admin",
        "role": "ADMIN",
    }).encode())
    signing_input = header + b"." + payload
    signature = b64url(hmac.new(SECRET, signing_input, hashlib.sha256).digest())
    print((signing_input + b"." + signature).decode())


if __name__ == "__main__":
    main()
