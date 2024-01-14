package 代码自测._001_日志;

import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.bridge.SLF4JBridgeHandler;

public class _001_jul和slf4j桥接 {

	@Test
	public void jul桥接器的使用() {
		java.util.logging.Logger julLogger = java.util.logging.Logger.getLogger(_001_jul和slf4j桥接.class.getName());
		julLogger.info("This log message will be redirected to Slf4J.");
		// 安装Slf4JBridgeHandler以桥接JUL到Slf4J
		SLF4JBridgeHandler.install();//加载一个SLF4JBridgeHandler 日志处理器到 跟Logger 处理器列表中  这个处理器就是 slf4j 提供的打印功能子logger 在打印时会
		//执行父logger的处理器  也就会执行注册到Root 的加载一个SLF4JBridgeHandler  达到输出的功能
		java.util.logging.Logger julLogger1 = java.util.logging.Logger.getLogger(_001_jul和slf4j桥接.class.getName());
		julLogger1.info("This log message will be redirected to Slf4J.");

		Logger aa = LoggerFactory.getLogger(_001_jul和slf4j桥接.class);
		aa.info("This log message will be redirected to Slf4J.");
	}


}
