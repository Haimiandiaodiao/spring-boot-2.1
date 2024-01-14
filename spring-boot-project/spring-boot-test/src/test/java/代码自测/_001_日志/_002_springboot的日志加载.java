package 代码自测._001_日志;

import org.junit.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;

public class _002_springboot的日志加载 {
	@Test
	public void 查看日志系统的初始化话() {
		SpringApplication application = new SpringApplication();
		application.setWebApplicationType(WebApplicationType.NONE);
		application.run();

		//debug 查看LoggingApplicationListener.onApplicationEvent
	}
}
