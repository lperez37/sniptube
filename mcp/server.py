"""MCP server for video-clips-api. Runs via stdio transport."""
import asyncio
import json
import sys

from tools import VideoClipsClient


async def handle_request(client: VideoClipsClient, request: dict) -> dict:
    """Handle a single MCP JSON-RPC request."""
    method = request.get("method", "")
    params = request.get("params", {})
    req_id = request.get("id")

    if method == "initialize":
        return {
            "jsonrpc": "2.0",
            "id": req_id,
            "result": {
                "protocolVersion": "2024-11-05",
                "capabilities": {"tools": {"listChanged": False}},
                "serverInfo": {"name": "video-clips-mcp", "version": "0.1.0"},
            },
        }

    if method == "notifications/initialized":
        return None  # notification, no response

    if method == "tools/list":
        return {
            "jsonrpc": "2.0",
            "id": req_id,
            "result": {
                "tools": [
                    {
                        "name": "video_download",
                        "description": "Download a YouTube video",
                        "inputSchema": {
                            "type": "object",
                            "properties": {"url": {"type": "string", "description": "YouTube URL"}},
                            "required": ["url"],
                        },
                    },
                    {
                        "name": "video_list",
                        "description": "List all downloaded videos",
                        "inputSchema": {"type": "object", "properties": {}},
                    },
                    {
                        "name": "video_info",
                        "description": "Get video metadata",
                        "inputSchema": {
                            "type": "object",
                            "properties": {"video_id": {"type": "string"}},
                            "required": ["video_id"],
                        },
                    },
                    {
                        "name": "video_clip",
                        "description": "Generate a video clip (lossless by default)",
                        "inputSchema": {
                            "type": "object",
                            "properties": {
                                "video_id": {"type": "string"},
                                "start_sec": {"type": "number"},
                                "end_sec": {"type": "number"},
                                "mode": {"type": "string", "enum": ["copy", "precise"], "default": "copy"},
                            },
                            "required": ["video_id", "start_sec", "end_sec"],
                        },
                    },
                    {
                        "name": "video_gif",
                        "description": "Generate a GIF from a video",
                        "inputSchema": {
                            "type": "object",
                            "properties": {
                                "video_id": {"type": "string"},
                                "start_sec": {"type": "number"},
                                "end_sec": {"type": "number"},
                                "width": {"type": "integer", "default": 480},
                                "fps": {"type": "integer", "default": 10},
                                "quality": {"type": "string", "enum": ["high", "fast"], "default": "high"},
                            },
                            "required": ["video_id", "start_sec", "end_sec"],
                        },
                    },
                    {
                        "name": "video_audio",
                        "description": "Extract audio (MP3) from a video, optionally trimmed to a time range",
                        "inputSchema": {
                            "type": "object",
                            "properties": {
                                "video_id": {"type": "string"},
                                "start_sec": {"type": "number", "description": "Start time in seconds (omit for full video)"},
                                "end_sec": {"type": "number", "description": "End time in seconds (omit for full video)"},
                            },
                            "required": ["video_id"],
                        },
                    },
                    {
                        "name": "youtube_search",
                        "description": "Search YouTube for videos by keyword",
                        "inputSchema": {
                            "type": "object",
                            "properties": {
                                "query": {"type": "string", "description": "Search query"},
                                "max_results": {"type": "integer", "default": 10, "description": "Max results (1-30)"},
                                "duration": {"type": "string", "enum": ["any", "short", "medium", "long"], "default": "any"},
                                "sort_by": {"type": "string", "enum": ["relevance", "date", "views"], "default": "relevance"},
                                "page": {"type": "integer", "default": 1, "description": "Page number (1-5)"},
                            },
                            "required": ["query"],
                        },
                    },
                    {
                        "name": "job_status",
                        "description": "Check the status of a job",
                        "inputSchema": {
                            "type": "object",
                            "properties": {"job_id": {"type": "string"}},
                            "required": ["job_id"],
                        },
                    },
                ],
            },
        }

    if method == "tools/call":
        tool_name = params.get("name", "")
        args = params.get("arguments", {})

        try:
            result = await client.call_tool(tool_name, args)
            return {
                "jsonrpc": "2.0",
                "id": req_id,
                "result": {
                    "content": [{"type": "text", "text": json.dumps(result, indent=2)}],
                },
            }
        except Exception as e:
            return {
                "jsonrpc": "2.0",
                "id": req_id,
                "result": {
                    "content": [{"type": "text", "text": f"Error: {e}"}],
                    "isError": True,
                },
            }

    return {
        "jsonrpc": "2.0",
        "id": req_id,
        "error": {"code": -32601, "message": f"Unknown method: {method}"},
    }


async def main():
    api_url = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8000"
    client = VideoClipsClient(api_url)

    reader = asyncio.StreamReader()
    protocol = asyncio.StreamReaderProtocol(reader)
    await asyncio.get_event_loop().connect_read_pipe(lambda: protocol, sys.stdin)

    writer_transport, writer_protocol = await asyncio.get_event_loop().connect_write_pipe(
        asyncio.streams.FlowControlMixin, sys.stdout
    )
    writer = asyncio.StreamWriter(writer_transport, writer_protocol, None, asyncio.get_event_loop())

    while True:
        line = await reader.readline()
        if not line:
            break
        line = line.decode().strip()
        if not line:
            continue

        try:
            request = json.loads(line)
        except json.JSONDecodeError:
            continue

        response = await handle_request(client, request)
        if response is not None:
            out = json.dumps(response) + "\n"
            writer.write(out.encode())
            await writer.drain()


if __name__ == "__main__":
    asyncio.run(main())
