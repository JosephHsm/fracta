"""MinIO에서 투자설명서 PDF를 읽는다.

Core가 바이트를 HTTP로 넘기지 않고 AI 서비스가 직접 읽는 이유:
수십 MB PDF를 이벤트 리스너 경유로 왕복시키지 않기 위해서, 그리고 재인덱싱을
Core를 거치지 않고 수행할 수 있게 하기 위해서다.
"""

from typing import Protocol

from minio import Minio


class ProspectusReader(Protocol):
    def read(self, file_key: str) -> bytes: ...


class MinioProspectusReader:

    def __init__(
        self, endpoint: str, access_key: str, secret_key: str, bucket: str, secure: bool
    ) -> None:
        self._client = Minio(
            endpoint, access_key=access_key, secret_key=secret_key, secure=secure
        )
        self._bucket = bucket

    def read(self, file_key: str) -> bytes:
        response = self._client.get_object(self._bucket, file_key)
        try:
            return response.read()
        finally:
            response.close()
            response.release_conn()


class InMemoryProspectusReader:
    """테스트 대역."""

    def __init__(self, files: dict[str, bytes] | None = None) -> None:
        self.files = files or {}

    def read(self, file_key: str) -> bytes:
        if file_key not in self.files:
            raise FileNotFoundError(file_key)
        return self.files[file_key]
