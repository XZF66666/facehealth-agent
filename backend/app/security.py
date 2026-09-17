from fastapi import Depends, HTTPException, Request, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer


bearer = HTTPBearer(auto_error=False)


async def authenticated_user(
    request: Request,
    credentials: HTTPAuthorizationCredentials | None = Depends(bearer),
) -> str | None:
    if credentials is not None and credentials.scheme.lower() == "bearer":
        user_id = await request.app.state.auth_store.authenticate(credentials.credentials)
        if user_id:
            return user_id
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="访问令牌无效或已失效",
        )
    if request.app.state.settings.auth_required:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="需要设备访问令牌",
        )
    return None


def resolve_user(authenticated: str | None, requested: str) -> str:
    return authenticated or requested
