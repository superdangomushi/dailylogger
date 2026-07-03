# aihelper local audio worker (Node.js + faster-whisper) Makefile
# Expected environment: each user's local Ubuntu PC.

NODE_MAJOR ?= 20

.DEFAULT_GOAL := help
.PHONY: help install system-deps npm-deps stt-deps gpu-driver gpu-check run clean

help:
	@echo "aihelper local audio worker Makefile"
	@echo ""
	@echo "  make install       Node.js + ffmpeg + faster-whisper setup (sudo used)"
	@echo "  make run           Start the polling audio worker"
	@echo "  make system-deps   Install Node.js $(NODE_MAJOR).x, Python venv, and ffmpeg (sudo)"
	@echo "  make npm-deps      Install Node dependencies"
	@echo "  make stt-deps      Install faster-whisper into stt/.venv (sudo)"
	@echo "  make gpu-driver    Install NVIDIA driver when needed (sudo; reboot required)"
	@echo "  make gpu-check     Check whether faster-whisper can see CUDA"
	@echo "  make clean         Remove node_modules, worker-audio, and stt/.venv"

install: system-deps npm-deps stt-deps
	@echo ""
	@echo "Setup complete. Run 'make run' and open the local UI to add accounts."

system-deps:
	@echo ">> Installing Node.js $(NODE_MAJOR).x, python3-venv, and ffmpeg"
	curl -fsSL https://deb.nodesource.com/setup_$(NODE_MAJOR).x | sudo -E bash -
	sudo apt-get install -y nodejs python3-venv ffmpeg
	@echo ">> Node: $$(node -v) / npm: $$(npm -v)"

npm-deps:
	npm install

# Audio transcription runs on the user's local PC. CUDA Toolkit is not required
# system-wide; cuBLAS/cuDNN are installed inside the venv by pip packages.
stt-deps:
	@echo ">> Installing faster-whisper into stt/.venv"
	sudo apt-get install -y python3-venv ffmpeg
	python3 -m venv stt/.venv
	stt/.venv/bin/pip install --upgrade pip
	stt/.venv/bin/pip install -r stt/requirements.txt
	@$(MAKE) --no-print-directory gpu-check

# RTX 50xx (Blackwell) needs driver 570 or newer. ubuntu-drivers picks a suitable version.
gpu-driver:
	@echo ">> Installing NVIDIA driver. Reboot after this target completes."
	sudo apt-get update
	sudo apt-get install -y ubuntu-drivers-common
	sudo ubuntu-drivers install
	@echo ">> After reboot, run 'make gpu-check'."

gpu-check:
	@if command -v nvidia-smi >/dev/null 2>&1; then \
	  nvidia-smi --query-gpu=name,driver_version,memory.total --format=csv,noheader; \
	else \
	  echo "nvidia-smi not found. CPU transcription still works; install a GPU driver with 'make gpu-driver' if needed."; \
	fi
	@if [ -x stt/.venv/bin/python3 ]; then \
	  stt/.venv/bin/python3 -c "import ctranslate2; n = ctranslate2.get_cuda_device_count(); print('CUDA devices visible to faster-whisper: ' + str(n))"; \
	else \
	  echo "stt/.venv is not ready yet. Run 'make stt-deps' first."; \
	fi

run:
	npm start

clean:
	rm -rf node_modules worker-audio stt/.venv
