"""벤치 투자자 JWT 생성 (표준 라이브러리만 사용).

stdin으로 investor id 목록(공백 구분)을 받아 tokens.json 배열을 stdout으로 낸다.
시크릿은 application.yml의 로컬 기본값과 같아야 한다 (JWT_SECRET 미설정 기준).
"""
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
    ids = [int(x) for x in sys.stdin.read().split()]
    now = int(time.time())
    header = b64url(json.dumps({"alg": "HS256", "typ": "JWT"}).encode())
    tokens = []
    for investor_id in ids:
        payload = b64url(json.dumps({
            "sub": str(investor_id),
            "iat": now,
            "exp": now + 43_200,
            "name": "bench",
            "role": "INVESTOR",
        }).encode())
        signing_input = header + b"." + payload
        signature = b64url(hmac.new(SECRET, signing_input, hashlib.sha256).digest())
        tokens.append((signing_input + b"." + signature).decode())
    print(json.dumps(tokens))


if __name__ == "__main__":
    main()
