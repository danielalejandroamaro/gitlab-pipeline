# Makefile for the GitLab Pipeline Watcher IntelliJ plugin.
#
# Everything is a thin wrapper over gradlew.bat. Default target builds the
# distributable .zip in build\distributions\.
#
# Quick start:
#   make            -> build the .zip
#   make install    -> build + open the folder so you can drop the .zip into IntelliJ
#   make run        -> launch a sandbox IDE with the plugin already loaded (easiest)

SHELL := cmd.exe
.SHELLFLAGS := /c

# Path to a JDK. Overridable from the command line:  make build JAVA_HOME=...
JAVA_HOME ?= C:\Users\daniel_amaro\.jdks\ms-21.0.11
GRADLEW := .\gradlew.bat
export JAVA_HOME

.DEFAULT_GOAL := build
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

build:
	@echo === Building plugin ===
	@$(GRADLEW) buildPlugin
	@echo.
	@echo === Build complete ===
	@dir /b build\distributions\*.zip 2>nul

rebuild: clean build

clean:
	@$(GRADLEW) clean

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

open-dist:
	@if exist build\distributions ( explorer build\distributions ) else ( echo No build\distributions yet - run "make build" first. && exit /b 1 )

zip:
	@dir /b /s build\distributions\*.zip 2>nul || echo No zip yet - run "make build" first.
