// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.execution.process.mediator

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.*
import com.intellij.execution.process.elevation.ElevationBundle
import com.intellij.execution.process.elevation.ElevationLogger
import com.intellij.execution.process.mediator.daemon.DaemonClientCredentials
import com.intellij.execution.process.mediator.daemon.ProcessMediatorDaemon
import com.intellij.execution.process.mediator.daemon.ProcessMediatorDaemonRuntimeClasspath
import com.intellij.execution.process.mediator.handshake.*
import com.intellij.execution.util.ExecUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.util.ProgressIndicatorUtils
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.io.FileUtil
import com.intellij.util.ExceptionUtil
import com.intellij.util.SystemProperties
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.io.BaseInputStreamReader
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.stub.MetadataUtils
import java.io.*
import java.net.InetAddress
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService

private typealias Port = Int

private val LOOPBACK_IP = InetAddress.getLoopbackAddress().hostAddress


object ProcessMediatorDaemonLauncher {
  fun launchDaemon(sudo: Boolean): ProcessMediatorDaemon {
    val appExecutorService = AppExecutorUtil.getAppExecutorService()

    // Unix sudo may take different forms, and not all of them are reliable in terms of process lifecycle management,
    // input/output redirection, and so on. To overcome the limitations we use an RSA-secured channel for initial communication
    // instead of process stdio, and launch it in a trampoline mode. In this mode the sudo'ed process forks the real daemon process,
    // relays the handshake message from it, and exits, so that the sudo process is done as soon as the handshake message is exchanged.
    // Using a trampoline also ensures that the launched process is certainly not a session leader, and allows it to become one.
    // In particular, this is a workaround for high CPU consumption of the osascript (used on macOS instead of sudo) process;
    // we want it to finish as soon as possible.
    val handshakeTransport = if (SystemInfo.isWindows) {
      HandshakeTransport.createProcessStdoutTransport()
    }
    else try {
      appExecutorService.submitAndAwaitCloseable { openUnixHandshakeTransport() }
    }
    catch (e: IOException) {
      throw ExecutionException(ElevationBundle.message("dialog.message.handshake.failed"), e)
    }
    val daemonLaunchOptions = handshakeTransport.getDaemonLaunchOptions().let {
      if (SystemInfo.isWindows) it else it.copy(trampoline = sudo, daemonize = sudo, leaderPid = ProcessHandle.current().pid())
    }

    val trampolineCommandLine = createJavaVmCommandLine(ProcessMediatorDaemonRuntimeClasspath.getProperties(),
                                                        ProcessMediatorDaemonRuntimeClasspath.getClasspathClasses())
      .withParameters(ProcessMediatorDaemonRuntimeClasspath.getMainClass().name)
      .withParameters(daemonLaunchOptions.asCmdlineArgs())

    return handshakeTransport.use {
      appExecutorService.submitAndAwait {
        val maybeSudoTrampolineCommandLine =
          if (!sudo) trampolineCommandLine
          else ExecUtil.sudoCommand(trampolineCommandLine, "Elevation daemon")

        val trampolineProcessHandler = handshakeTransport.createDaemonProcessHandler(maybeSudoTrampolineCommandLine).also {
          it.startNotify()
        }
        val trampolineProcessHandle = trampolineProcessHandler.process.toHandle().apply {
          onExit().whenComplete { _, _ ->
            handshakeTransport.close()
          }
        }

        val handshake = handshakeTransport.readHandshake() ?: throw ProcessCanceledException()
        val daemonProcessHandle =
          if (SystemInfo.isWindows) trampolineProcessHandle  // can't get access a process owned by another user
          else ProcessHandle.of(handshake.pid).orElseThrow(::ProcessCanceledException)

        ProcessMediatorDaemonImpl(daemonProcessHandle,
                                  handshake.port,
                                  DaemonClientCredentials(handshake.token))
      }
    }
  }

  private fun openUnixHandshakeTransport(): HandshakeTransport {
    return try {
      HandshakeTransport.createUnixFifoTransport(path = FileUtil.generateRandomTemporaryPath().toPath())
    }
    catch (e0: IOException) {
      ElevationLogger.LOG.warn("Unable to create file-based handshake channel; falling back to socket streams", e0)
      try {
        HandshakeTransport.createSocketTransport()
      }
      catch (e1: IOException) {
        e1.addSuppressed(e0)
        throw e1
      }
    }
      // neither a named pipe nor an open port is safe from prying eyes
      .encrypted()
  }

  private fun HandshakeTransport.createDaemonProcessHandler(daemonCommandLine: GeneralCommandLine): BaseOSProcessHandler {
    val processHandler =
      if (this !is ProcessStdoutHandshakeTransport) {
        OSProcessHandler.Silent(daemonCommandLine)
      }
      else {
        object : OSProcessHandler.Silent(daemonCommandLine) {
          override fun createProcessOutReader(): Reader {
            return BaseInputStreamReader(InputStream.nullInputStream())  // don't let the process handler touch the stdout stream
          }
        }.also {
          initStream(it.process.inputStream)
        }
      }
    processHandler.addProcessListener(object : ProcessAdapter() {
      override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
        ElevationLogger.LOG.info("Daemon [$outputType]: ${event.text.substringBeforeLast("\n")}")
      }

      override fun processTerminated(event: ProcessEvent) {
        val exitCodeString = ProcessTerminatedListener.stringifyExitCode(event.exitCode)
        ElevationLogger.LOG.info("Daemon process terminated with exit code ${exitCodeString}")
      }
    })
    return processHandler
  }
}

private fun <R> ExecutorService.submitAndAwait(block: () -> R): R {
  val future = CompletableFuture.supplyAsync(block, this)
  return awaitWithCheckCanceled(future)
}

private fun <R : Closeable?> ExecutorService.submitAndAwaitCloseable(block: () -> R): R {
  val future = CompletableFuture.supplyAsync(block, this)
  return try {
    awaitWithCheckCanceled(future)
  }
  catch (e: Throwable) {
    future.whenComplete { closeable, _ -> closeable?.close() }
    throw e
  }
}

private fun <R> awaitWithCheckCanceled(future: CompletableFuture<R>): R {
  try {
    if (ApplicationManager.getApplication() == null) return future.join()
    return ProgressIndicatorUtils.awaitWithCheckCanceled(future)
  }
  catch (e: Throwable) {
    throw ExceptionUtil.findCause(e, java.util.concurrent.ExecutionException::class.java)?.cause ?: e
  }
}

private class ProcessMediatorDaemonImpl(private val processHandle: ProcessHandle,
                                        private val port: Port,
                                        private val credentials: DaemonClientCredentials) : ProcessMediatorDaemon {

  override fun createChannel(): ManagedChannel {
    return ManagedChannelBuilder.forAddress(LOOPBACK_IP, port).usePlaintext()
      .intercept(MetadataUtils.newAttachHeadersInterceptor(credentials.asMetadata()))
      .build().also { channel ->
        processHandle.onExit().whenComplete { _, _ -> channel.shutdown() }
      }
  }

  override fun stop() = Unit

  override fun blockUntilShutdown() {
    processHandle.onExit().get()
  }
}


private fun createJavaVmCommandLine(properties: Map<String, String>,
                                    classpathClasses: MutableList<Class<*>>): GeneralCommandLine {
  val javaVmExecutablePath = SystemProperties.getJavaHome() + File.separator + "bin" + File.separator + "java"
  val propertyArgs = properties.map { (k, v) -> "-D$k=$v" }
  val classpath = classpathClasses.mapNotNullTo(LinkedHashSet()) { it.getResourcePath() }.joinToString(File.pathSeparator)

  return GeneralCommandLine(javaVmExecutablePath)
    .withParameters(propertyArgs)
    .withParameters("-cp", classpath)
}

private fun Class<*>.getResourcePath(): String? {
  return FileUtil.toCanonicalPath(PathManager.getResourceRoot(this, "/" + name.replace('.', '/') + ".class"))
}
