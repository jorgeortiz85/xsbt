/* sbt -- Simple Build Tool
 * Copyright 2008, 2009, 2010 Mark Harrah
 */
package sbt

import scala.collection.mutable.{Buffer, HashMap, ListBuffer}

sealed trait LogEvent extends NotNull
final class Success(val msg: String) extends LogEvent
final class Log(val level: Level.Value, val msg: String) extends LogEvent
final class Trace(val exception: Throwable) extends LogEvent
final class SetLevel(val newLevel: Level.Value) extends LogEvent
final class SetTrace(val level: Int) extends LogEvent
final class ControlEvent(val event: ControlEvent.Value, val msg: String) extends LogEvent

object ControlEvent extends Enumeration
{
	val Start, Header, Finish = Value
}

abstract class Logger extends xsbt.CompileLogger with IvyLogger
{
	def getLevel: Level.Value
	def setLevel(newLevel: Level.Value)
	def setTrace(flag: Int)
	def getTrace: Int
	final def traceEnabled = getTrace >= 0
	def ansiCodesSupported = false

	def atLevel(level: Level.Value) = level.id >= getLevel.id
	def trace(t: => Throwable): Unit
	final def verbose(message: => String): Unit = debug(message)
	final def debug(message: => String): Unit = log(Level.Debug, message)
	final def info(message: => String): Unit = log(Level.Info, message)
	final def warn(message: => String): Unit = log(Level.Warn, message)
	final def error(message: => String): Unit = log(Level.Error, message)
	def success(message: => String): Unit
	def log(level: Level.Value, message: => String): Unit
	def control(event: ControlEvent.Value, message: => String): Unit

	def logAll(events: Seq[LogEvent]): Unit
	/** Defined in terms of other methods in Logger and should not be called from them. */
	final def log(event: LogEvent)
	{
		event match
		{
			case s: Success => success(s.msg)
			case l: Log => log(l.level, l.msg)
			case t: Trace => trace(t.exception)
			case setL: SetLevel => setLevel(setL.newLevel)
			case setT: SetTrace => setTrace(setT.level)
			case c: ControlEvent => control(c.event, c.msg)
		}
	}

	import xsbti.F0
	def debug(msg: F0[String]): Unit = log(Level.Debug, msg)
	def warn(msg: F0[String]): Unit = log(Level.Warn, msg)
	def info(msg: F0[String]): Unit = log(Level.Info, msg)
	def error(msg: F0[String]): Unit = log(Level.Error, msg)
	def trace(msg: F0[Throwable]) = trace(msg.apply)
	def log(level: Level.Value, msg: F0[String]): Unit = log(level, msg.apply)
}

/** Implements the level-setting methods of Logger.*/
abstract class BasicLogger extends Logger
{
	private var traceEnabledVar = java.lang.Integer.MAX_VALUE
	private var level: Level.Value = Level.Info
	def getLevel = level
	def setLevel(newLevel: Level.Value) { level = newLevel }
	def setTrace(level: Int) { traceEnabledVar = level }
	def getTrace = traceEnabledVar
}

final class SynchronizedLogger(delegate: Logger) extends Logger
{
	override lazy val ansiCodesSupported = delegate.ansiCodesSupported
	def getLevel = { synchronized { delegate.getLevel } }
	def setLevel(newLevel: Level.Value) { synchronized { delegate.setLevel(newLevel) } }
	def setTrace(level: Int) { synchronized { delegate.setTrace(level) } }
	def getTrace: Int = { synchronized { delegate.getTrace } }

	def trace(t: => Throwable) { synchronized { delegate.trace(t) } }
	def log(level: Level.Value, message: => String) { synchronized { delegate.log(level, message) } }
	def success(message: => String) { synchronized { delegate.success(message) } }
	def control(event: ControlEvent.Value, message: => String) { synchronized { delegate.control(event, message) } }
	def logAll(events: Seq[LogEvent]) { synchronized { delegate.logAll(events) } }
}

final class MultiLogger(delegates: List[Logger]) extends BasicLogger
{
	override lazy val ansiCodesSupported = delegates.forall(_.ansiCodesSupported)
	override def setLevel(newLevel: Level.Value)
	{
		super.setLevel(newLevel)
		dispatch(new SetLevel(newLevel))
	}
	override def setTrace(level: Int)
	{
		super.setTrace(level)
		dispatch(new SetTrace(level))
	}
	def trace(t: => Throwable) { dispatch(new Trace(t)) }
	def log(level: Level.Value, message: => String) { dispatch(new Log(level, message)) }
	def success(message: => String) { dispatch(new Success(message)) }
	def logAll(events: Seq[LogEvent]) { delegates.foreach(_.logAll(events)) }
	def control(event: ControlEvent.Value, message: => String) { delegates.foreach(_.control(event, message)) }
	private def dispatch(event: LogEvent) { delegates.foreach(_.log(event)) }
}

/** A filter logger is used to delegate messages but not the logging level to another logger.  This means
* that messages are logged at the higher of the two levels set by this logger and its delegate.
* */
final class FilterLogger(delegate: Logger) extends BasicLogger
{
	override lazy val ansiCodesSupported = delegate.ansiCodesSupported
	def trace(t: => Throwable)
	{
		if(traceEnabled)
			delegate.trace(t)
	}
	override def setTrace(level: Int) { delegate.setTrace(level) }
	override def getTrace = delegate.getTrace 
	def log(level: Level.Value, message: => String)
	{
		if(atLevel(level))
			delegate.log(level, message)
	}
	def success(message: => String)
	{
		if(atLevel(Level.Info))
			delegate.success(message)
	}
	def control(event: ControlEvent.Value, message: => String)
	{
		if(atLevel(Level.Info))
			delegate.control(event, message)
	}
	def logAll(events: Seq[LogEvent]): Unit = delegate.logAll(events)
}

/** A logger that can buffer the logging done on it by currently executing Thread and
* then can flush the buffer to the delegate logger provided in the constructor.  Use
* 'startRecording' to start buffering and then 'play' from to flush the buffer for the
* current Thread to the backing logger.  The logging level set at the
* time a message is originally logged is used, not the level at the time 'play' is
* called.
*
* This class assumes that it is the only client of the delegate logger.
*
* This logger is thread-safe.
* */
final class BufferedLogger(delegate: Logger) extends Logger
{
	override lazy val ansiCodesSupported = delegate.ansiCodesSupported
	private[this] val buffers = wrap.Wrappers.weakMap[Thread, Buffer[LogEvent]]
	private[this] var recordingAll = false

	private[this] def getOrCreateBuffer = buffers.getOrElseUpdate(key, createBuffer)
	private[this] def buffer = if(recordingAll) Some(getOrCreateBuffer) else buffers.get(key)
	private[this] def createBuffer = new ListBuffer[LogEvent]
	private[this] def key = Thread.currentThread

	@deprecated def startRecording() = recordAll()
	/** Enables buffering for logging coming from the current Thread. */
	def record(): Unit = synchronized { buffers(key) = createBuffer }
	/** Enables buffering for logging coming from all Threads. */
	def recordAll(): Unit = synchronized{ recordingAll = true }
	def buffer[T](f: => T): T =
	{
		record()
		try { f }
		finally { Control.trap(stop()) }
	}
	def bufferAll[T](f: => T): T =
	{
		recordAll()
		try { f }
		finally { Control.trap(stopAll()) }
	}

	/** Flushes the buffer to the delegate logger for the current thread.  This method calls logAll on the delegate
	* so that the messages are written consecutively. The buffer is cleared in the process. */
	def play(): Unit =
 		synchronized
		{
			for(buffer <- buffers.get(key))
				delegate.logAll(wrap.Wrappers.readOnly(buffer))
		}
	def playAll(): Unit =
		synchronized
		{
			for(buffer <- buffers.values)
				delegate.logAll(wrap.Wrappers.readOnly(buffer))
		}
	/** Clears buffered events for the current thread and disables buffering. */
	def clear(): Unit = synchronized { buffers -= key }
	/** Clears buffered events for all threads and disables all buffering. */
	def clearAll(): Unit = synchronized { buffers.clear(); recordingAll = false }
	/** Plays buffered events for the current thread and disables buffering. */
	def stop(): Unit =
		synchronized
		{
			play()
			clear()
		}
	def stopAll(): Unit =
		synchronized
		{
			playAll()
			clearAll()
		}

	def setLevel(newLevel: Level.Value): Unit =
		synchronized
		{
			buffer.foreach{_  += new SetLevel(newLevel) }
			delegate.setLevel(newLevel)
		}
	def getLevel = synchronized { delegate.getLevel }
	def getTrace = synchronized { delegate.getTrace }
	def setTrace(level: Int): Unit =
		synchronized
		{
			buffer.foreach{_  += new SetTrace(level) }
			delegate.setTrace(level)
		}

	def trace(t: => Throwable): Unit =
		doBufferableIf(traceEnabled, new Trace(t), _.trace(t))
	def success(message: => String): Unit =
		doBufferable(Level.Info, new Success(message), _.success(message))
	def log(level: Level.Value, message: => String): Unit =
		doBufferable(level, new Log(level, message), _.log(level, message))
	def logAll(events: Seq[LogEvent]): Unit =
		synchronized
		{
			buffer match
			{
				case Some(b) => b ++= events
				case None => delegate.logAll(events)
			}
		}
	def control(event: ControlEvent.Value, message: => String): Unit =
		doBufferable(Level.Info, new ControlEvent(event, message), _.control(event, message))
	private def doBufferable(level: Level.Value, appendIfBuffered: => LogEvent, doUnbuffered: Logger => Unit): Unit =
		doBufferableIf(atLevel(level), appendIfBuffered, doUnbuffered)
	private def doBufferableIf(condition: => Boolean, appendIfBuffered: => LogEvent, doUnbuffered: Logger => Unit): Unit =
		synchronized
		{
			if(condition)
			{
				buffer match
				{
					case Some(b) => b += appendIfBuffered
					case None => doUnbuffered(delegate)
				}
			}
		}
}

object ConsoleLogger
{
	private val formatEnabled = ansiSupported && !formatExplicitlyDisabled

	private[this] def formatExplicitlyDisabled = java.lang.Boolean.getBoolean("sbt.log.noformat")
	private[this] def ansiSupported =
		try { jline.Terminal.getTerminal.isANSISupported }
		catch { case e: Exception => !isWindows }

	private[this] def os = System.getProperty("os.name")
	private[this] def isWindows = os.toLowerCase.indexOf("windows") >= 0
}

/** A logger that logs to the console.  On supported systems, the level labels are
* colored.
*
* This logger is not thread-safe.*/
class ConsoleLogger extends BasicLogger
{
	override def ansiCodesSupported = ConsoleLogger.formatEnabled
	def messageColor(level: Level.Value) = Console.RESET
	def labelColor(level: Level.Value) =
		level match
		{
			case Level.Error => Console.RED
			case Level.Warn => Console.YELLOW
			case _ => Console.RESET
		}
	def successLabelColor = Console.GREEN
	def successMessageColor = Console.RESET
	override def success(message: => String)
	{
		if(atLevel(Level.Info))
			log(successLabelColor, Level.SuccessLabel, successMessageColor, message)
	}
	def trace(t: => Throwable): Unit =
		System.out.synchronized
		{
			val traceLevel = getTrace
			if(traceLevel >= 0)
				System.out.synchronized { System.out.print(StackTrace.trimmed(t, traceLevel)) }
		}
	def log(level: Level.Value, message: => String)
	{
		if(atLevel(level))
			log(labelColor(level), level.toString, messageColor(level), message)
	}
	private def setColor(color: String)
	{
		if(ansiCodesSupported)
			System.out.synchronized { System.out.print(color) }
	}
	private def log(labelColor: String, label: String, messageColor: String, message: String): Unit =
		System.out.synchronized
		{
			for(line <- message.split("""\n"""))
			{
				setColor(Console.RESET)
				System.out.print('[')
				setColor(labelColor)
				System.out.print(label)
				setColor(Console.RESET)
				System.out.print("] ")
				setColor(messageColor)
				System.out.print(line)
				setColor(Console.RESET)
				System.out.println()
			}
		}

	def logAll(events: Seq[LogEvent]) = System.out.synchronized { events.foreach(log) }
	def control(event: ControlEvent.Value, message: => String)
		{ log(labelColor(Level.Info), Level.Info.toString, Console.BLUE, message) }
}

/** An enumeration defining the levels available for logging.  A level includes all of the levels
* with id larger than its own id.  For example, Warn (id=3) includes Error (id=4).*/
object Level extends Enumeration with NotNull
{
	val Debug = Value(1, "debug")
	val Info = Value(2, "info")
	val Warn = Value(3, "warn")
	val Error = Value(4, "error")
	/** Defines the label to use for success messages.  A success message is logged at the info level but
	* uses this label.  Because the label for levels is defined in this module, the success
	* label is also defined here. */
	val SuccessLabel = "success"

	// added because elements was renamed to iterator in 2.8.0 nightly
	def levels = Debug :: Info :: Warn :: Error :: Nil
	/** Returns the level with the given name wrapped in Some, or None if no level exists for that name. */
	def apply(s: String) = levels.find(s == _.toString)
	/** Same as apply, defined for use in pattern matching. */
	private[sbt] def unapply(s: String) = apply(s)
}
/** Provides a `java.io.Writer` interface to a `Logger`.  Content is line-buffered and logged at `level`.
* A line is delimited by `nl`, which is by default the platform line separator.*/
final class LoggerWriter(delegate: Logger, level: Level.Value, nl: String) extends java.io.Writer
{
	def this(delegate: Logger, level: Level.Value) = this(delegate, level, FileUtilities.Newline)
	
	private[this] val buffer = new StringBuilder

	override def close() = flush()
	override def flush(): Unit =
		synchronized {
			if(buffer.length > 0)
			{
				log(buffer.toString)
				buffer.clear()
			}
		}
	override def write(content: Array[Char], offset: Int, length: Int): Unit =
		synchronized {
			buffer.append(content, offset, length)
			process()
		}

	private[this] def process()
	{
		val i = buffer.indexOf(nl)
		if(i >= 0)
		{
			log(buffer.substring(0, i))
			buffer.delete(0, i + nl.length)
			process()
		}
	}
	private[this] def log(s: String): Unit  =  delegate.log(level, s)
}