package co.freeside.betamax.proxy

import co.freeside.betamax.Recorder
import co.freeside.betamax.httpclient.BetamaxRoutePlanner
import co.freeside.betamax.proxy.jetty.ProxyServer
import co.freeside.betamax.proxy.jetty.SimpleServer
import co.freeside.betamax.util.server.EchoHandler
import groovyx.net.http.RESTClient
import spock.lang.*

import static org.apache.http.HttpHeaders.VIA

@Issue('https://github.com/robfletcher/betamax/issues/16')
class IgnoreHostsSpec extends Specification {

	@Shared @AutoCleanup('deleteDir') File tapeRoot = new File(System.properties.'java.io.tmpdir', 'tapes')
	@Shared @AutoCleanup('stop') SimpleServer endpoint = new SimpleServer()
	@AutoCleanup('ejectTape') Recorder recorder = new Recorder(tapeRoot: tapeRoot)
	@AutoCleanup('stop') ProxyServer proxy = new ProxyServer()
	RESTClient http

	void setupSpec() {
		endpoint.start(EchoHandler)
	}

	void setup() {
		http = new RESTClient()
		BetamaxRoutePlanner.configure(http.client)

		recorder.insertTape('ignore hosts spec')
	}

	void cleanup() {
		recorder.restoreOriginalProxySettings()
	}

	@Unroll('does not proxy a request to #requestURI when ignoring #ignoreHosts')
	void 'does not proxy requests to ignored hosts'() {
		given: 'proxy is configured to ignore local connections'
		recorder.ignoreHosts = [ignoreHosts]
		proxy.start(recorder)
		recorder.overrideProxySettings()

		when: 'a request is made'
		def response = http.get(uri: requestURI)

		then: 'the request is not intercepted by the proxy'
		!response.headers[VIA]

		and: 'nothing is recorded to the tape'
		recorder.tape.size() == old(recorder.tape.size())

		where:
		ignoreHosts               | requestURI
		endpoint.url.toURI().host | endpoint.url
		'localhost'               | "http://localhost:${endpoint.url.toURI().port}"
		'127.0.0.1'               | "http://localhost:${endpoint.url.toURI().port}"
		endpoint.url.toURI().host | "http://localhost:${endpoint.url.toURI().port}"
	}

	@Unroll('does not proxy a request to #requestURI when ignoreLocalhost is true')
	void 'does not proxy request to localhost when ignored'() {
		given: 'proxy is configured to ignore local connections'
		recorder.ignoreLocalhost = true
		proxy.start(recorder)
		recorder.overrideProxySettings()

		when: 'a request is made'
		def response = http.get(uri: requestURI)

		then: 'the request is not intercepted by the proxy'
		!response.headers[VIA]

		and: 'nothing is recorded to the tape'
		recorder.tape.size() == old(recorder.tape.size())

		where:
		requestURI << [endpoint.url, "http://localhost:${endpoint.url.toURI().port}"]
	}

}
