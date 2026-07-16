# -*- coding: utf-8 -*-
"""
用户认证模块
提供 JWT Token 签发/校验、密码加密、用户存储
"""
import os
import uuid
import json
import hashlib
import secrets
from pathlib import Path
from datetime import datetime, timedelta, timezone
from typing import Optional

import jwt
from fastapi import Depends, HTTPException, status
from fastapi.security import HTTPBearer, HTTPAuthorizationCredentials
from pydantic import BaseModel


# ============================================================
# 配置
# ============================================================
JWT_SECRET = os.getenv("JWT_SECRET", secrets.token_hex(32))
JWT_ALGORITHM = "HS256"
JWT_EXPIRE_HOURS = 24  # Token 有效期 24 小时

security_scheme = HTTPBearer()


# ============================================================
# 数据模型
# ============================================================
class UserRegister(BaseModel):
    username: str
    password: str


class UserLogin(BaseModel):
    username: str
    password: str


class UserInfo(BaseModel):
    id: str
    username: str
    created_at: str


class TokenResponse(BaseModel):
    access_token: str
    token_type: str = "bearer"
    user: UserInfo


# ============================================================
# 密码工具
# ============================================================
def hash_password(password: str) -> str:
    """SHA256 哈希密码（带随机盐）"""
    salt = secrets.token_hex(16)
    hashed = hashlib.sha256((password + salt).encode()).hexdigest()
    return f"{salt}${hashed}"


def verify_password(password: str, hashed: str) -> bool:
    """验证密码"""
    parts = hashed.split("$", 1)
    if len(parts) != 2:
        return False
    salt, stored_hash = parts
    computed = hashlib.sha256((password + salt).encode()).hexdigest()
    return secrets.compare_digest(computed, stored_hash)


# ============================================================
# JWT 工具
# ============================================================
def create_token(user_id: str, username: str) -> str:
    """签发 JWT Token"""
    payload = {
        "sub": user_id,
        "username": username,
        "iat": datetime.now(timezone.utc),
        "exp": datetime.now(timezone.utc) + timedelta(hours=JWT_EXPIRE_HOURS),
    }
    return jwt.encode(payload, JWT_SECRET, algorithm=JWT_ALGORITHM)


def decode_token(token: str) -> dict:
    """解析 JWT Token，返回 payload"""
    try:
        return jwt.decode(token, JWT_SECRET, algorithms=[JWT_ALGORITHM])
    except jwt.ExpiredSignatureError:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="登录已过期，请重新登录",
        )
    except jwt.InvalidTokenError:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="无效的认证令牌",
        )


# ============================================================
# 用户存储（JSON 文件持久化）
# ============================================================
# 数据文件路径
DATA_DIR = Path(__file__).parent / "data"
USERS_FILE = DATA_DIR / "users.json"


class UserStore:
    """用户存储，基于 JSON 文件持久化"""

    def __init__(self):
        self._users: dict[str, dict] = {}
        self._load()

    def _load(self):
        """从 JSON 文件加载用户数据"""
        try:
            if USERS_FILE.exists():
                with open(USERS_FILE, "r", encoding="utf-8") as f:
                    self._users = json.load(f)
                print(f"已从文件加载 {len(self._users)} 个用户: {USERS_FILE}")
        except Exception as e:
            print(f"加载用户文件失败: {e}，将使用空数据")
            self._users = {}

    def _save(self):
        """保存用户数据到 JSON 文件"""
        try:
            DATA_DIR.mkdir(parents=True, exist_ok=True)
            with open(USERS_FILE, "w", encoding="utf-8") as f:
                json.dump(self._users, f, ensure_ascii=False, indent=2)
        except Exception as e:
            print(f"保存用户文件失败: {e}")

    def create_user(self, username: str, password: str) -> Optional[dict]:
        """注册用户，返回用户信息或 None（用户名已存在）"""
        for u in self._users.values():
            if u["username"] == username:
                return None
        user_id = str(uuid.uuid4())
        user = {
            "id": user_id,
            "username": username,
            "password": hash_password(password),
            "created_at": datetime.now(timezone.utc).isoformat(),
        }
        self._users[user_id] = user
        self._save()  # 持久化到文件
        return {"id": user["id"], "username": user["username"], "created_at": user["created_at"]}

    def authenticate(self, username: str, password: str) -> Optional[dict]:
        """验证用户登录"""
        for u in self._users.values():
            if u["username"] == username and verify_password(password, u["password"]):
                return {"id": u["id"], "username": u["username"], "created_at": u["created_at"]}
        return None

    def get_user(self, user_id: str) -> Optional[dict]:
        """通过 ID 获取用户"""
        user = self._users.get(user_id)
        if user:
            return {"id": user["id"], "username": user["username"], "created_at": user["created_at"]}
        return None


# 全局用户存储实例
user_store = UserStore()


# ============================================================
# FastAPI 鉴权依赖
# ============================================================
async def get_current_user(
    credentials: HTTPAuthorizationCredentials = Depends(security_scheme),
) -> UserInfo:
    """从请求的 Bearer Token 中解析当前登录用户"""
    payload = decode_token(credentials.credentials)
    user_id = payload.get("sub")
    if not user_id:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="无效的认证令牌",
        )
    user = user_store.get_user(user_id)
    if not user:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="用户不存在",
        )
    return UserInfo(**user)
