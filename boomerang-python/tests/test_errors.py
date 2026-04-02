import pytest

from boomerang.errors import (
    BoomerangConflictError,
    BoomerangError,
    BoomerangForbiddenError,
    BoomerangServiceUnavailableError,
    BoomerangUnauthorizedError,
    raise_for_status,
)


class TestBoomerangError:
    def test_base_error(self):
        err = BoomerangError(500, "Internal Server Error")
        assert err.status_code == 500
        assert err.message == "Internal Server Error"
        assert str(err) == "[500] Internal Server Error"

    def test_is_exception(self):
        assert issubclass(BoomerangError, Exception)

    def test_catchable_by_base(self):
        with pytest.raises(BoomerangError):
            raise BoomerangUnauthorizedError("bad token")


class TestSubclasses:
    def test_unauthorized(self):
        err = BoomerangUnauthorizedError("Invalid JWT")
        assert err.status_code == 401
        assert str(err) == "[401] Invalid JWT"

    def test_unauthorized_default(self):
        err = BoomerangUnauthorizedError()
        assert str(err) == "[401] Unauthorized"

    def test_forbidden(self):
        err = BoomerangForbiddenError("URL not allowed")
        assert err.status_code == 403

    def test_conflict(self):
        err = BoomerangConflictError("Duplicate key", retry_after_seconds=30)
        assert err.status_code == 409
        assert err.retry_after_seconds == 30

    def test_conflict_no_retry(self):
        err = BoomerangConflictError()
        assert err.retry_after_seconds is None

    def test_service_unavailable(self):
        err = BoomerangServiceUnavailableError("Pool full")
        assert err.status_code == 503


class TestRaiseForStatus:
    @pytest.mark.parametrize(
        "code,expected_cls",
        [
            (401, BoomerangUnauthorizedError),
            (403, BoomerangForbiddenError),
            (409, BoomerangConflictError),
            (503, BoomerangServiceUnavailableError),
        ],
    )
    def test_known_codes(self, code, expected_cls):
        with pytest.raises(expected_cls) as exc_info:
            raise_for_status(code, "msg")
        assert exc_info.value.status_code == code

    def test_unknown_code_raises_base(self):
        with pytest.raises(BoomerangError) as exc_info:
            raise_for_status(502, "Bad Gateway")
        assert exc_info.value.status_code == 502
        assert not isinstance(exc_info.value, BoomerangUnauthorizedError)
