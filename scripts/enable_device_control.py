from pathlib import Path

path = Path("app/src/main/java/com/mensagemstudio/jarvis/AgentToolExecutor.java")
text = path.read_text(encoding="utf-8")
needle = '''    public ToolResult execute(String name, JSONObject arguments) {
        try {
            switch (name) {
'''
replacement = '''    public ToolResult execute(String name, JSONObject arguments) {
        try {
            ToolResult deviceResult = DeviceControlTools.tryExecute(context, name, arguments);
            if (deviceResult != null) return deviceResult;
            switch (name) {
'''
if replacement not in text:
    if needle not in text:
        raise SystemExit("AgentToolExecutor execute() anchor not found")
    text = text.replace(needle, replacement, 1)
path.write_text(text, encoding="utf-8")
print("DeviceControlTools wired into AgentToolExecutor")
