# Makefile for the GitLab Pipeline Watcher IntelliJ plugin.
#
# Everything is a thin wrapper over gradlew.bat. Default target builds the
# distributable .zip in build\distributions\.
#
# Quick start:
#   make            -> build the .zip
#   make install    -> build + open the folder so you can drop the .zip into IntelliJ
#   make run        -> launch a sandbox IDE with the plugin already loaded (easiest)

# ---------------------------------------------------------------------------
# Shell config — IMPORTANT on Windows.
#
# GNU Make defaults to invoking recipes via `sh.exe`. We don't have sh on this
# box, so we force every recipe line to run through `cmd.exe /c <line>`. That's
# why every recipe below is written in cmd syntax (`@echo.`, `if exist`, `&&`,
# `2>nul`, backslash paths, etc.) instead of POSIX sh.
# ---------------------------------------------------------------------------
SHELL := cmd.exe
.SHELLFLAGS := /c

# Path to a JDK used by Gradle. The default points at the JDK installed on
# Daniel's machine; override on the CLI if you're building elsewhere:
#   make build JAVA_HOME=C:\path\to\other\jdk
# `export` propagates it into the cmd subshell Gradle runs in.
JAVA_HOME ?= C:\Users\daniel_amaro\.jdks\ms-21.0.11
GRADLEW := .\gradlew.bat
export JAVA_HOME

# Build the .zip when you type plain `make` with no target.
.DEFAULT_GOAL := build

# All targets are "phony" (none of them produce a file named after the target),
# so always run their recipes — never let make skip them because of a same-named
# file/directory that happens to exist (e.g. `build\`).
.PHONY: help build rebuild clean install dev run verify zip open-dist

help:
	@echo.
	@echo GitLab Pipeline Watcher - build targets
	@echo ----------------------------------------
	@echo   make dev        ** Fast dev loop ** - launch sandbox IDE with current build
	@echo   make build      Compile and package the plugin (build\distributions\*.zip)
	@echo   make rebuild    Clean build, then package (force-fresh zip)
	@echo   make clean      Remove build\ output
	@echo   make install    Build + open the dist folder so you can drop the .zip into IntelliJ
	@echo   make run        Alias for "make dev"
	@echo   make verify     Run the IntelliJ Platform plugin verifier
	@echo   make zip        Print the path of the generated .zip
	@echo   make open-dist  Open build\distributions\ in Explorer
	@echo.
	@echo Install / update WITHOUT restarting your IntelliJ:
	@echo   * Easiest: "make dev"  - opens a sandbox IDE; relaunch to get the new version.
	@echo   * Real IDE: this plugin is dynamic (no restart required).
	@echo       1) make install     (builds + opens build\distributions)
	@echo       2) IntelliJ: Settings ^> Plugins ^> gear ^> Install Plugin from Disk
	@echo       3) Click APPLY/OK, NOT "Restart" - the plugin hot-swaps in place.
	@echo       If only "Restart" is offered, disable+re-enable the plugin instead.
	@echo.
	@echo Using JAVA_HOME=$(JAVA_HOME)

# `@echo.` (with the dot, no space) is the cmd.exe idiom for "print a blank
# line". Plain `@echo` without a dot prints "ECHO is on." instead.
# `@` (make-level) suppresses echoing the command itself; `2>nul` (cmd-level)
# swallows "File Not Found" when the dir is empty.
build:
	@echo === Building plugin ===
	@$(GRADLEW) buildPlugin
	@echo.
	@echo === Build complete ===
	@dir /b build\distributions\*.zip 2>nul

rebuild: clean build

clean:
	@$(GRADLEW) clean

# Two target names, ONE recipe — `make dev` and `make run` both launch the
# sandbox IDE. This is GNU Make's "multi-target rule" shorthand; equivalent to
# writing the same recipe twice.
dev run:
	@echo === Launching sandbox IDE (plugin pre-loaded - close window and rerun to update) ===
	@$(GRADLEW) runIde

verify:
	@$(GRADLEW) verifyPlugin

install: build open-dist
	@echo.
	@echo Next step in IntelliJ:
	@echo   File ^> Settings ^> Plugins ^> gear icon ^> "Install Plugin from Disk..." ^> pick the .zip
	@echo.

# cmd `if exist ... ( ... ) else ( ... )` — opens Explorer when the dist
# folder is there, otherwise prints a hint and exits non-zero so a chained
# `make install` aborts cleanly. The `&&` between the message and `exit /b 1`
# is cmd's conditional chain (run next only if previous succeeded).
open-dist:
	@if exist build\distributions ( explorer build\distributions ) else ( echo No build\distributions yet - run "make build" first. && exit /b 1 )

# `/s` recurses; print full paths of every .zip under build\distributions.
# If `dir` itself fails (no matches anywhere), the `||` fallback prints a hint.
zip:
	@dir /b /s build\distributions\*.zip 2>nul || echo No zip yet - run "make build" first.
