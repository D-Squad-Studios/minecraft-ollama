# Ollama Setup Guide

This guide will walk you through installing Ollama, pulling the Llama 3.2 model, and exposing the API server on your local machine.

## Prerequisites

- macOS 11 Big Sur or later, Windows 10/11, or a modern Linux distribution
- At least 8GB of RAM (16GB+ recommended for larger models)
- Sufficient disk space (models can range from 2GB to 100GB+)

## Installation

### macOS

1. Download the Ollama installer from the official website:
   ```bash
   curl -fsSL https://ollama.com/install.sh | sh
   ```

   Alternatively, download the `.dmg` file directly from [ollama.com/download](https://ollama.com/download) and install it manually.

2. Verify the installation:
   ```bash
   ollama --version
   ```

### Windows

1. Download the Ollama installer for Windows from [ollama.com/download](https://ollama.com/download)

2. Run the downloaded `.exe` installer and follow the installation wizard

3. Open Command Prompt or PowerShell and verify the installation:
   ```powershell
   ollama --version
   ```

### Linux

1. Install Ollama using the official install script:
   ```bash
   curl -fsSL https://ollama.com/install.sh | sh
   ```

2. Verify the installation:
   ```bash
   ollama --version
   ```

3. (Optional) If you want Ollama to run as a systemd service:
   ```bash
   sudo systemctl enable ollama
   sudo systemctl start ollama
   ```

## Pulling Llama 3.2

Once Ollama is installed, pull the Llama 3.2 model:

```bash
ollama pull llama3.2:latest
```

This will download the model files to your local machine. The download size is approximately 2GB, so it may take a few minutes depending on your internet connection.

## Starting the Ollama Server

### Default Server (All Platforms)

By default, Ollama serves on `http://localhost:11434`. To start the server:

```bash
ollama serve
```

The server will start and be accessible at `http://localhost:11434`.

**Note:** On macOS and Windows, the Ollama application typically runs the server automatically in the background. You may not need to run `ollama serve` manually.

### Verifying the Server

To verify that the server is running, open a new terminal window and test the API:

```bash
curl http://localhost:11434/api/tags
```

You should see a JSON response listing your installed models, including `llama3.2:latest`.

## Configuration

### Changing the Default Port

If you need to use a different port, set the `OLLAMA_HOST` environment variable:

**macOS/Linux:**
```bash
export OLLAMA_HOST=0.0.0.0:11434
ollama serve
```

**Windows (PowerShell):**
```powershell
$env:OLLAMA_HOST="0.0.0.0:11434"
ollama serve
```

## Troubleshooting

### Port Already in Use

If port 11434 is already in use, you'll see an error. Either:
- Stop the conflicting service
- Use a different port by setting `OLLAMA_HOST` as shown above and change the baseUrl in `src/main/java/net/kevinthedang/ollamamod/chat/OllamaSettings.java`

### Model Not Found

If you get a "model not found" error, ensure you've pulled the model:
```bash
ollama list  # Check installed models
ollama pull llama3.2:latest  # Pull if not installed
```

### Server Not Responding

- Check if Ollama is running: `ps aux | grep ollama` (macOS/Linux) or Task Manager (Windows)
- Restart the Ollama service or application
- Check firewall settings if accessing from another device

## Additional Commands

- List installed models: `ollama list`
- Remove a model: `ollama rm llama3.2:latest`
- Show model information: `ollama show llama3.2:latest`
- Stop the server: Press `Ctrl+C` in the terminal running `ollama serve`