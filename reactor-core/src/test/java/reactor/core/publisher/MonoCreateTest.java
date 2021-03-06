/*
 * Copyright (c) 2011-2017 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactor.core.publisher;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.assertj.core.api.Assertions;
import org.junit.Test;
import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.core.Exceptions;
import reactor.core.Scannable;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

public class MonoCreateTest {

	@Test
	public void createStreamFromMonoCreate() {
		AtomicInteger onDispose = new AtomicInteger();
		AtomicInteger onCancel = new AtomicInteger();
		StepVerifier.create(Mono.create(s -> {
							s.onDispose(onDispose::getAndIncrement)
							 .onCancel(onCancel::getAndIncrement)
							 .success("test1");
						}))
		            .expectNext("test1")
		            .verifyComplete();
		assertThat(onDispose.get()).isEqualTo(1);
		assertThat(onCancel.get()).isEqualTo(0);
	}

	@Test
	public void createStreamFromMonoCreateHide() {
		StepVerifier.create(Mono.create(s -> s.success("test1")).hide())
		            .expectNext("test1")
		            .verifyComplete();
	}

	@Test
	public void createStreamFromMonoCreateError() {
		AtomicInteger onDispose = new AtomicInteger();
		AtomicInteger onCancel = new AtomicInteger();
		StepVerifier.create(Mono.create(s -> {
							s.onDispose(onDispose::getAndIncrement)
							 .onCancel(onCancel::getAndIncrement)
							 .error(new Exception("test"));
						}))
		            .verifyErrorMessage("test");
		assertThat(onDispose.get()).isEqualTo(1);
		assertThat(onCancel.get()).isEqualTo(0);
	}

	@Test
	public void cancellation() {
		AtomicInteger onDispose = new AtomicInteger();
		AtomicInteger onCancel = new AtomicInteger();
		StepVerifier.create(Mono.create(s -> {
							s.onDispose(onDispose::getAndIncrement)
							 .onCancel(onCancel::getAndIncrement);
						}))
		            .thenAwait()
		            .consumeSubscriptionWith(Subscription::cancel)
		            .thenCancel()
		            .verify();
		assertThat(onDispose.get()).isEqualTo(1);
		assertThat(onCancel.get()).isEqualTo(1);
	}

	@Test
	public void monoCreateDisposables() {
		AtomicInteger dispose1 = new AtomicInteger();
		AtomicInteger dispose2 = new AtomicInteger();
		AtomicInteger cancel1 = new AtomicInteger();
		AtomicInteger cancel2 = new AtomicInteger();
		AtomicInteger cancellation = new AtomicInteger();
		Mono<String> created = Mono.create(s -> {
			s.onDispose(dispose1::getAndIncrement)
			 .onCancel(cancel1::getAndIncrement);
			s.onDispose(dispose2::getAndIncrement);
			assertThat(dispose2.get()).isEqualTo(1);
			s.onCancel(cancel2::getAndIncrement);
			assertThat(cancel2.get()).isEqualTo(1);
			s.onDispose(cancellation::getAndIncrement);
			assertThat(cancellation.get()).isEqualTo(1);
			assertThat(dispose1.get()).isEqualTo(0);
			assertThat(cancel1.get()).isEqualTo(0);
			s.success();
		});

		StepVerifier.create(created)
		            .verifyComplete();

		assertThat(dispose1.get()).isEqualTo(1);
		assertThat(cancel1.get()).isEqualTo(0);
	}

	@Test
	public void monoCreateOnCancel() {
		AtomicBoolean cancelled = new AtomicBoolean();
		Mono.create(s -> s.onCancel(() -> cancelled.set(true)).success("test")).block();
		assertThat(cancelled.get()).isFalse();

		Mono.create(s -> s.onCancel(() -> cancelled.set(true)).success()).block();
		assertThat(cancelled.get()).isFalse();
	}

	@Test
	public void monoCreateCancelOnNext() {
		AtomicInteger onCancel = new AtomicInteger();
		AtomicInteger onDispose = new AtomicInteger();
		AtomicReference<Subscription> subscription = new AtomicReference<>();
		Mono<String> created = Mono.create(s -> {
			s.onDispose(onDispose::getAndIncrement)
			 .onCancel(onCancel::getAndIncrement)
			 .success("done");
		});
		created = created.doOnSubscribe(s -> subscription.set(s))
						 .doOnNext(n -> subscription.get().cancel());

		StepVerifier.create(created)
					.expectNext("done")
					.verifyComplete();

		assertThat(onDispose.get()).isEqualTo(1);
		assertThat(onCancel.get()).isEqualTo(0);
	}

	@Test
	public void createStreamFromMonoCreate2() {
		StepVerifier.create(Mono.create(MonoSink::success)
		                        .publishOn(Schedulers.parallel()))
		            .verifyComplete();
	}

	@Test
	public void monoCreateOnRequest() {
		Mono<Integer> created = Mono.create(s -> {
			s.onRequest(n -> s.success(5));
		});

		StepVerifier.create(created, 0)
					.expectSubscription()
					.thenAwait()
					.thenRequest(1)
					.expectNext(5)
					.expectComplete()
					.verify();
	}

	@Test
	public void sinkApiEmptySuccessAfterEmptySuccessIsIgnored() {
		Mono<String> secondIsEmptySuccess = Mono.create(sink -> {
			sink.success();
			sink.success();
		});

		StepVerifier.create(secondIsEmptySuccess)
	                .verifyComplete();
	}

	@Test
	public void sinkApiSuccessAfterEmptySuccessIsIgnored() {
		Mono<String> secondIsValuedSuccess = Mono.create(sink -> {
			sink.success();
			sink.success("foo");
		});

		StepVerifier.create(secondIsValuedSuccess)
	                .verifyComplete();
	}

	@Test
	public void sinkApiErrorAfterEmptySuccessBubblesAndDrops() {
		Mono<String> secondIsError = Mono.create(sink -> {
			sink.success();
			sink.error(new IllegalArgumentException("boom"));
		});

		Assertions.assertThatExceptionOfType(RuntimeException.class)
		          .isThrownBy(secondIsError::subscribe)
		          .matches(Exceptions::isBubbling)
		          .satisfies(e -> assertThat(Exceptions.unwrap(e)).hasMessage("boom"));

		StepVerifier.create(secondIsError)
		            .expectComplete()
		            .verifyThenAssertThat()
		            .hasDroppedErrorWithMessage("boom");
	}

	@Test
	public void sinkApiEmptySuccessAfterSuccessIsIgnored() {
		Mono<String> secondIsEmptySuccess = Mono.create(sink -> {
			sink.success("foo");
			sink.success();
		});

		StepVerifier.create(secondIsEmptySuccess)
		            .expectNext("foo")
		            .verifyComplete();
	}

	@Test
	public void sinkApiSuccessAfterSuccessIsIgnored() {
		Mono<String> secondIsValuedSuccess = Mono.create(sink -> {
			sink.success("foo");
			sink.success("bar");
		});

		StepVerifier.create(secondIsValuedSuccess)
		            .expectNext("foo")
		            .verifyComplete();
	}

	@Test
	public void sinkApiErrorAfterSuccessBubblesAndDrops() {
		Mono<String> secondIsError = Mono.create(sink -> {
			sink.success("foo");
			sink.error(new IllegalArgumentException("boom"));
		});

		Assertions.assertThatExceptionOfType(RuntimeException.class)
		          .isThrownBy(secondIsError::subscribe)
		          .matches(Exceptions::isBubbling)
		          .satisfies(e -> assertThat(Exceptions.unwrap(e)).hasMessage("boom"));

		StepVerifier.create(secondIsError)
		            .expectNext("foo")
		            .expectComplete()
		            .verifyThenAssertThat()
		            .hasDroppedErrorWithMessage("boom");
	}

	@Test
	public void sinkApiEmptySuccessAfterErrorIsIgnored() {
		Mono<String> secondIsEmptySuccess = Mono.create(sink -> {
			sink.error(new IllegalArgumentException("boom"));
			sink.success();
		});

		StepVerifier.create(secondIsEmptySuccess)
	                .verifyErrorMessage("boom");
	}

	@Test
	public void sinkApiSuccessAfterErrorIsIgnored() {
		Mono<String> secondIsValuedSuccess = Mono.create(sink -> {
			sink.error(new IllegalArgumentException("boom"));
			sink.success("bar");
		});

		StepVerifier.create(secondIsValuedSuccess)
	                .verifyErrorMessage("boom");
	}

	@Test
	public void sinkApiErrorAfterErrorBubblesAndDrops() {
		Mono<String> secondIsError = Mono.create(sink -> {
			sink.error(new IllegalArgumentException("boom1"));
			sink.error(new IllegalArgumentException("boom2"));
		});

		Assertions.assertThatExceptionOfType(RuntimeException.class)
		          .isThrownBy(() -> secondIsError.subscribe(v -> {}, e -> {}))
		          .matches(Exceptions::isBubbling, "exception is bubbling")
		          .satisfies(e -> assertThat(Exceptions.unwrap(e)).hasMessage("boom2"));

		StepVerifier.create(secondIsError)
		            .expectErrorMessage("boom1")
		            .verifyThenAssertThat()
		            .hasDroppedErrorWithMessage("boom2");
	}

	@Test
	public void scanDefaultMonoSink() {
		CoreSubscriber<String> actual = new LambdaMonoSubscriber<>(null, e -> {}, null, null);
		MonoCreate.DefaultMonoSink<String> test = new MonoCreate.DefaultMonoSink<>(actual);

		assertThat(test.scan(Scannable.ScannableAttr.ACTUAL)).isSameAs(actual);

		assertThat(test.scan(Scannable.BooleanAttr.TERMINATED)).isFalse();
		test.success();
		assertThat(test.scan(Scannable.BooleanAttr.TERMINATED)).isTrue();
		assertThat(test.scan(Scannable.BooleanAttr.CANCELLED)).isTrue();
	}

	@Test
	public void scanDefaultMonoSinkCancelTerminates() {
		CoreSubscriber<String> actual = new LambdaMonoSubscriber<>(null, e -> {}, null, null);
		MonoCreate.DefaultMonoSink<String> test = new MonoCreate.DefaultMonoSink<>(actual);

		assertThat(test.scan(Scannable.BooleanAttr.CANCELLED)).isFalse();
		assertThat(test.scan(Scannable.BooleanAttr.TERMINATED)).isFalse();
		test.cancel();
		assertThat(test.scan(Scannable.BooleanAttr.CANCELLED)).isTrue();
		assertThat(test.scan(Scannable.BooleanAttr.TERMINATED)).isTrue();
	}
}

