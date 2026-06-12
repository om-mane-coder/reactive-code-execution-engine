package com.codesandbox.engine;

import com.github.dockerjava.api.DockerClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class SandboxEngineApplicationTests {

	@Autowired
	private DockerClient dockerClient;

	@Test
	void testDockerConnection() {
		try {
			System.out.println(">>> Pinging Docker daemon...");
			dockerClient.pingCmd().exec();
			System.out.println(">>> Docker Ping SUCCESSFUL!");
		} catch (Exception e) {
			System.err.println(">>> Docker Ping FAILED: " + e.getMessage());
			e.printStackTrace();
		}
	}
}
