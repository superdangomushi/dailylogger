# aihelper local audio worker (C++ + faster-whisper) Makefile
# Expected environment: each user's local Ubuntu PC.
#
# The worker binary itself is C++ (communication / local UI / process control).
# Speech recognition stays in Python (stt/transcribe.py, faster-whisper).

CXX ?= g++
CXXFLAGS ?= -std=c++17 -O2 -Wall -Wextra
CPPFLAGS = -Icpp/third_party -DCPPHTTPLIB_OPENSSL_SUPPORT
LDLIBS = -lssl -lcrypto -pthread

BIN = audio-worker
SRCS = cpp/src/main.cpp
HDRS = $(wildcard cpp/src/*.hpp) cpp/third_party/httplib.h cpp/third_party/json.hpp

.DEFAULT_GOAL := help
.PHONY: help install system-deps build stt-deps gpu-driver gpu-check run clean

help:
	@echo "aihelper local audio worker Makefile"
	@echo ""
	@echo "  make install       C++ build tools + ffmpeg + faster-whisper setup (sudo used)"
	@echo "  make build         Build the C++ worker binary ($(BIN))"
	@echo "  make run           Start the polling audio worker"
	@echo "  make system-deps   Install g++, OpenSSL headers, Python venv, and ffmpeg (sudo)"
	@echo "  make stt-deps      Install faster-whisper into stt/.venv (sudo)"
	@echo "  make gpu-driver    Install NVIDIA driver when needed (sudo; reboot required)"
	@echo "  make gpu-check     Check whether faster-whisper can see CUDA"
	@echo "  make clean         Remove the binary, worker-audio, and stt/.venv"

install: system-deps build stt-deps
	@echo ""
	@echo "Setup complete. Run 'make run' and open the local UI to add accounts."

system-deps:
	@echo ">> Installing g++, libssl-dev, python3-venv, and ffmpeg"
	sudo apt-get update
	sudo apt-get install -y g++ make libssl-dev python3-venv ffmpeg
	@echo ">> g++: $$(g++ --version | head -n1)"

$(BIN): $(SRCS) $(HDRS)
	$(CXX) $(CXXFLAGS) $(CPPFLAGS) -o $(BIN) $(SRCS) $(LDLIBS)

build: $(BIN)

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

run: $(BIN)
	./$(BIN)

clean:
	rm -rf $(BIN) worker-audio stt/.venv
