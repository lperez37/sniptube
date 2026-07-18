"""Tests for GET /share-target (Web Share Target redirect in app/main.py)."""


async def test_share_target_extracts_url_from_text(client):
    r = await client.get(
        "/share-target",
        params={"text": "check this out https://youtu.be/dQw4w9WgXcQ so funny"},
    )
    assert r.status_code == 303
    assert r.headers["location"] == "/#/download?share=https%3A//youtu.be/dQw4w9WgXcQ"


async def test_share_target_uses_url_param_when_text_empty(client):
    r = await client.get(
        "/share-target",
        params={"url": "https://www.youtube.com/watch?v=dQw4w9WgXcQ", "text": ""},
    )
    assert r.status_code == 303
    assert (
        r.headers["location"]
        == "/#/download?share=https%3A//www.youtube.com/watch%3Fv%3DdQw4w9WgXcQ"
    )


async def test_share_target_plain_text_passed_through(client):
    r = await client.get("/share-target", params={"text": "funny cats"})
    assert r.status_code == 303
    assert r.headers["location"] == "/#/download?share=funny%20cats"


async def test_share_target_all_params_empty_still_redirects(client):
    r = await client.get("/share-target")
    assert r.status_code == 303
    assert r.headers["location"] == "/#/download?share="
