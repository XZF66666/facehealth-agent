from uuid import uuid4

from fastapi.testclient import TestClient

from app.main import app


TODAY = {
    "heart_rate": 76,
    "respiratory_rate": 16,
    "hrv": 48,
    "stress_score": 62,
    "fatigue_score": 55,
    "video_quality": "合格",
}


def test_health_endpoint():
    with TestClient(app) as client:
        response = client.get("/health")
        assert response.status_code == 200
        assert response.json()["status"] == "ok"


def test_analyze_works_without_model_key():
    payload = {
        "user_id": "test_user",
        "user_message": "今天有点累",
        "today_metrics": TODAY,
        "weekly_summary": {"avg_hrv": 52, "trend": "整体平稳"},
        "user_context": {"sleep_hours": 5.5, "symptoms": ["疲劳"]},
    }
    with TestClient(app) as client:
        settings = app.state.health_agent.llm.settings
        original_key = settings.llm_api_key
        settings.llm_api_key = ""
        try:
            response = client.post("/api/agent/analyze", json=payload)
            assert response.status_code == 200
            body = response.json()
            assert "today_status" in body
            assert body["model_used"] is False
        finally:
            settings.llm_api_key = original_key


def test_chat_returns_conversation_id():
    with TestClient(app) as client:
        response = client.post(
            "/api/agent/chat",
            json={"user_id": "test_user", "message": "今天适合运动吗", "today_metrics": TODAY},
        )
        assert response.status_code == 200
        assert response.json()["conversation_id"]


def test_stream_chat_uses_context_and_is_idempotent():
    conversation_id = uuid4().hex
    client_message_id = uuid4().hex
    payload = {
        "user_id": "test_user",
        "conversation_id": conversation_id,
        "client_message_id": client_message_id,
        "message": "我现在应该运动吗",
        "today_metrics": TODAY,
        "weekly_records": [{**TODAY, "date": "2026-07-23"}],
        "user_context": {"sleep_hours": 5.0, "symptoms": ["胸痛"]},
        "user_note": "昨晚睡得较晚",
        "stream": True,
    }
    with TestClient(app) as client:
        settings = app.state.health_agent.llm.settings
        original_key = settings.llm_api_key
        settings.llm_api_key = ""
        try:
            first = client.post("/api/agent/chat", json=payload)
            second = client.post("/api/agent/chat", json=payload)
            assert first.status_code == 200
            assert second.status_code == 200
            assert "event: meta" in first.text
            assert f'"conversation_id": "{conversation_id}"' in first.text
            assert "event: delta" in first.text
            assert "及时就医" in first.text
            assert "event: done" in first.text
            assert second.text.count("event: done") == 1
        finally:
            settings.llm_api_key = original_key
            client.delete(f"/api/agent/chat/{conversation_id}")


def test_authenticated_device_syncs_records_and_agent_uses_server_tools():
    with TestClient(app) as client:
        session = client.post(
            "/api/auth/anonymous", json={"device_name": "pytest-android"}
        )
        assert session.status_code == 200
        token = session.json()["access_token"]
        headers = {"Authorization": f"Bearer {token}"}
        records = [
            {
                "client_record_id": "pytest-record-1",
                "date": "2026-07-25",
                "metrics": {**TODAY, "stress_score": 48, "hrv": 55},
                "context": {"sleep_hours": 7.5},
            },
            {
                "client_record_id": "pytest-record-2",
                "date": "2026-07-26",
                "metrics": {**TODAY, "stress_score": 64, "hrv": 45},
                "context": {"sleep_hours": 6.0},
            },
        ]
        sync = client.post(
            "/api/health/records/sync",
            headers=headers,
            json={"records": records},
        )
        assert sync.status_code == 200
        assert sync.json()["accepted"] == 2

        settings = app.state.health_agent.llm.settings
        original_key = settings.llm_api_key
        settings.llm_api_key = ""
        try:
            response = client.post(
                "/api/agent/chat",
                headers=headers,
                json={
                    "user_id": "forged-client-user",
                    "message": "看看本周趋势",
                },
            )
            assert response.status_code == 200
            body = response.json()
            assert body["intent"] == "weekly_trend"
            assert body["data_source"] == "server"
            assert "TrendTool" in body["tools_used"]
        finally:
            settings.llm_api_key = original_key


def test_profile_is_scoped_to_authenticated_user():
    with TestClient(app) as client:
        first = client.post("/api/auth/anonymous", json={}).json()
        second = client.post("/api/auth/anonymous", json={}).json()
        first_headers = {"Authorization": f"Bearer {first['access_token']}"}
        second_headers = {"Authorization": f"Bearer {second['access_token']}"}
        update = client.put(
            "/api/health/profile",
            headers=first_headers,
            json={
                "age_range": "20-29",
                "activity_level": "中等",
                "focus_areas": ["压力"],
                "response_style": "concise",
                "baseline_days": 28,
            },
        )
        assert update.status_code == 200
        own = client.get("/api/health/profile", headers=first_headers).json()
        other = client.get("/api/health/profile", headers=second_headers).json()
        assert own["profile"]["response_style"] == "concise"
        assert other["profile"]["response_style"] == "balanced"
