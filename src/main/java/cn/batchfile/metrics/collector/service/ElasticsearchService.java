package cn.batchfile.metrics.collector.service;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import javax.annotation.PostConstruct;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

import cn.batchfile.metrics.collector.config.ElasticsearchConfig;
import cn.batchfile.metrics.collector.domain.MetricData;
import cn.batchfile.metrics.collector.domain.RawData;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

@Service
public class ElasticsearchService {
	private static final Logger LOG = LoggerFactory.getLogger(ElasticsearchService.class);
	private static ThreadLocal<ObjectMapper> MAPPER = new ThreadLocal<ObjectMapper>() {
		protected ObjectMapper initialValue() {
			return new ObjectMapper();
		};
	};
	private Counter writeCounter;
	private Counter errorCounter;
	private Timer writeTimer;
	
	@Autowired
	private ElasticsearchConfig elasticsearchConfig;
	
	@Autowired
	private QueueService queueService;
	
	@Autowired
	private MetricService metricService;
	
	@Autowired
	private RestTemplate restTemplate;

	public ElasticsearchService(MeterRegistry registry) {
		writeCounter = Counter.builder("elasticsearch.write.ok.count").register(registry);
		errorCounter = Counter.builder("elasticsearch.write.error.count").register(registry);
		writeTimer = Timer.builder("elasticsearch.write.time").register(registry);
	}

	@PostConstruct
	public void init() {
		ExecutorService service = Executors.newFixedThreadPool(elasticsearchConfig.getWorker());
		for (int i = 0; i < elasticsearchConfig.getWorker(); i++) {
			service.submit(() ->{
				loop();
			});
		}
	}
	
	private void loop() {
		while (true) {
			try {
				write();
			} catch (Exception e) {
				LOG.error("error in loop", e);
			}
			
			try {
				Thread.sleep(500);
			} catch (Exception e) {
				LOG.error("error when sleep", e);
			}
		}
	}
	
	private void write() {
		List<RawData> datas = queueService.get(elasticsearchConfig.getBulkMaxSize());
		while (datas.size() > 0) {
			write(datas);
			datas = queueService.get(elasticsearchConfig.getBulkMaxSize());
		}
	}
	
	private void write(List<RawData> datas) {
		List<MetricData> metrics = new ArrayList<>();
		datas.forEach(data -> {
			metrics.addAll(metricService.compose(data));
		});
		LOG.debug("get metrics, size: {}", metrics.size());
		
		final String indexName = new SimpleDateFormat(elasticsearchConfig.getIndex()).format(new Date());
		List<String> lines = metrics.stream().map(metric -> composeLine(metric, indexName)).collect(Collectors.toList());
		LOG.debug("get lines, size: {}", lines.size());
		
		List<String> hosts = elasticsearchConfig.getHosts();
		String host = hosts.size() == 1 ? hosts.get(0) : hosts.get(new Random().nextInt(hosts.size()));
		
		String url = String.format("http://%s/_bulk", host);
		String body = StringUtils.join(lines, "\n");
		
		writeTimer.record(() -> {
			ResponseEntity<String> resp = restTemplate.postForEntity(url, body, String.class);
			HttpStatus status = resp.getStatusCode();
			if (status.is2xxSuccessful()) {
				writeCounter.increment();
				LOG.debug("write data into elasticsearch, size: {}", datas.size());
			} else {
				errorCounter.increment();
				LOG.error("elasticsearch error, status: {}, message: {}", status.value(), resp.toString());
			}
		});
	}
	
	private String composeLine(MetricData metric, String indexName) {
		String s = String.format("{\"index\":{\"_index\":\"%s\",\"_type\":\"metric\"}}\n", indexName);
		try { 
			s += MAPPER.get().writeValueAsString(metric);
			return s;
		} catch (Exception e) {
			return null;
		}
	}
	
}