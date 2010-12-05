/*
 * Copyright 2010 Twitter, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.twitter.configgy

import java.io.File
import java.math.BigInteger
import java.net.URLClassLoader
import java.security.MessageDigest
import java.util.Random
import java.util.jar.JarFile
import scala.collection.mutable
import scala.io.Source
import scala.tools.nsc.{Global, Settings}
import scala.tools.nsc.interpreter.AbstractFileClassLoader
import scala.tools.nsc.io.VirtualDirectory
import scala.tools.nsc.reporters.AbstractReporter
import scala.tools.nsc.util.{BatchSourceFile, Position}

/**
 * Evaluate a file or string and return the result.
 */
object Eval {
  // do not look at the man behind the curtain!
  private val compilerPath = jarPathOfClass("scala.tools.nsc.Interpreter")
  private val libPath = jarPathOfClass("scala.ScalaObject")

  private val jvmId = java.lang.Math.abs(new Random().nextInt())

  val compiler = new StringCompiler(2)

  /**
   * Eval[Int]("1 + 1") // => 2
   */
  def apply[T](code: String): T = {
    val id = uniqueId(code)
    val className = "Evaluator__" + id
    val cls = compiler(wrapCodeInClass(className, code), className, id)
    cls.getConstructor().newInstance().asInstanceOf[() => Any].apply().asInstanceOf[T]
  }

  /**
   * Eval[Int](new File("..."))
   */
  def apply[T](file: File): T = {
    apply(Source.fromFile(file).mkString)
  }

  private def uniqueId(code: String): String = {
    val digest = MessageDigest.getInstance("SHA-1").digest(code.getBytes())
    val sha = new BigInteger(1, digest).toString(16)
    sha + "_" + jvmId
  }

  /*
   * Wrap source code in a new class with an apply method.
   */
  private def wrapCodeInClass(className: String, code: String) = {
    "class " + className + " extends (() => Any) {\n" +
    "  def apply() = {\n" +
    code + "\n" +
    "  }\n" +
    "}\n"
  }

  /*
   * For a given FQ classname, trick the resource finder into telling us the containing jar.
   */
  private def jarPathOfClass(className: String) = {
    val resource = className.split('.').mkString("/", "/", ".class")
    val path = getClass.getResource(resource).getPath
    val indexOfFile = path.indexOf("file:") + 5
    val indexOfSeparator = path.lastIndexOf('!')
    path.substring(indexOfFile, indexOfSeparator)
  }

  /*
   * Try to guess our app's classpath.
   * This is probably fragile.
   */
  lazy val impliedClassPath: List[String] = {
    val currentClassPath = this.getClass.getClassLoader.asInstanceOf[URLClassLoader].getURLs.
      map(_.toString).filter(_.startsWith("file:")).map(_.substring(5)).toList

    // if there's just one thing in the classpath, and it's a jar, assume an executable jar.
    currentClassPath ::: (if (currentClassPath.size == 1 && currentClassPath(0).endsWith(".jar")) {
      val jarFile = currentClassPath(0)
      val relativeRoot = new File(jarFile).getParentFile()
      val nestedClassPath = new JarFile(jarFile).getManifest.getMainAttributes.getValue("Class-Path")
      if (nestedClassPath eq null) {
        Nil
      } else {
        nestedClassPath.split(" ").map { f => new File(relativeRoot, f).getAbsolutePath }.toList
      }
    } else {
      Nil
    })
  }

  /**
   * Dynamic scala compiler. Lots of (slow) state is created, so it may be advantageous to keep
   * around one of these and reuse it.
   */
  class StringCompiler(lineOffset: Int) {
    val virtualDirectory = new VirtualDirectory("(memory)", None)

    val cache = new mutable.HashMap[String, Class[_]]()

    val settings = new Settings
    settings.deprecation.value = true // enable detailed deprecation warnings
    settings.unchecked.value = true // enable detailed unchecked warnings
    settings.outputDirs.setSingleOutput(virtualDirectory)

    val pathList = List(compilerPath, libPath)
    settings.bootclasspath.value = pathList.mkString(File.pathSeparator)
    settings.classpath.value = (pathList ::: impliedClassPath).mkString(File.pathSeparator)

    val reporter = new AbstractReporter {
      val settings = StringCompiler.this.settings
      val messages = new mutable.ListBuffer[List[String]]

      def display(pos: Position, message: String, severity: Severity) {
        severity.count += 1
        val severityName = severity match {
          case ERROR   => "error: "
          case WARNING => "warning: "
          case _ => ""
        }
        messages += (severityName + "line " + (pos.line - lineOffset) + ": " + message) ::
          (if (pos.isDefined) {
            pos.inUltimateSource(pos.source).lineContent.stripLineEnd ::
              (" " * (pos.column - 1) + "^") ::
              Nil
          } else {
            Nil
          })
      }

      def displayPrompt {
        // no.
      }

      override def reset {
        // grumpy comment about these side-effect methods not taking parens.
        super.reset
        messages.clear()
      }
    }

    val global = new Global(settings, reporter)

    /*
     * Class loader for finding classes compiled by this StringCompiler.
     * After each reset, this class loader will not be able to find old compiled classes.
     */
    val classLoader = new AbstractFileClassLoader(virtualDirectory, this.getClass.getClassLoader)

    def reset() {
      // grumpy comment about these side-effect methods not taking parens.
      virtualDirectory.clear
      reporter.reset
    }

    /**
     * Compile scala code. It can be found using the above class loader.
     */
    def apply(code: String) {
      val compiler = new global.Run
      val sourceFiles = List(new BatchSourceFile("(inline)", code))
      compiler.compileSources(sourceFiles)

      if (reporter.hasErrors || reporter.WARNING.count > 0) {
        throw new CompilerException(reporter.messages.toList)
      }
    }

    /**
     * Reset the compiler, compile a new class, load it, and return it. Thread-safe.
     */
    def apply(code: String, className: String, id: String): Class[_] = synchronized {
      cache.get(id) match {
        case Some(cls) =>
          cls
        case None =>
          reset()
          apply(code)
          val cls = classLoader.loadClass(className)
          cache(id) = cls
          cls
      }
    }
  }

  class CompilerException(val messages: List[List[String]]) extends Exception("Compiler exception")
}
