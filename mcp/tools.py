"""HTTP client for video-clips-api, used by the MCP server."""
import asyncio
from urllib.request import Request, urlopen
from urllib.parse import urlencode
import json


class VideoClipsClient:
    def __init__(self, base_url: str = "http://localhost:8000"):
        self.base_url = base_url.rstrip("/")

    def _request(self, method: str, path: str, body: dict | None = None) -> dict:
        url = f"{self.base_url}{path}"
        data = json.dumps(body).encode() if body else None
        headers = {"Content-Type": "application/json"} if body else {}
        req = Request(url, data=data, headers=headers, method=method)
        with urlopen(req, timeout=300) as resp:
            return json.loads(resp.read().decode())

    async def call_tool(self, name: str, args: dict) -> dict:
        """Dispatch a tool call to the appropriate API endpoint."""
        loop = asyncio.get_event_loop()

        if name == "video_download":
            return await loop.run_in_executor(
                None, self._request, "POST", "/videos", {"url": args["url"]}
            )

        elif name == "video_list":
            return await loop.run_in_executor(
                None, self._request, "GET", "/videos", None
            )

        elif name == "video_info":
            return await loop.run_in_executor(
                None, self._request, "GET", f"/videos/{args['video_id']}", None
            )

        elif name == "video_clip":
            body = {
                "start_sec": args["start_sec"],
                "end_sec": args["end_sec"],
                "mode": args.get("mode", "copy"),
            }
            return await loop.run_in_executor(
                None, self._request, "POST", f"/videos/{args['video_id']}/clips", body
            )

        elif name == "video_gif":
            body = {
                "start_sec": args["start_sec"],
                "end_sec": args["end_sec"],
                "width": args.get("width", 480),
                "fps": args.get("fps", 10),
                "quality": args.get("quality", "high"),
            }
            return await loop.run_in_executor(
                None, self._request, "POST", f"/videos/{args['video_id']}/gifs", body
            )

        elif name == "video_audio":
            body = {}
            if "start_sec" in args:
                body["start_sec"] = args["start_sec"]
            if "end_sec" in args:
                body["end_sec"] = args["end_sec"]
            return await loop.run_in_executor(
                None, self._request, "POST", f"/videos/{args['video_id']}/audio",
                body if body else None
            )

        elif name == "youtube_search":
            params = {"q": args["query"]}
            for key in ("max_results", "duration", "sort_by", "page"):
                if key in args:
                    params[key] = args[key]
            return await loop.run_in_executor(
                None, self._request, "GET", f"/search?{urlencode(params)}", None
            )

        elif name == "job_status":
            return await loop.run_in_executor(
                None, self._request, "GET", f"/jobs/{args['job_id']}", None
            )

        else:
            raise ValueError(f"Unknown tool: {name}")
