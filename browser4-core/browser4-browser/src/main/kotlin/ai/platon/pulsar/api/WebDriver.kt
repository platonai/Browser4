package ai.platon.pulsar.api

/**
 * Copyright (c) Vincent Zhang, ivincent.zhang@gmail.com, Platon.AI.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
/**
 * Compatibility shim for the WebDriver interface that was moved to
 * [ai.platon.browser4.api.WebDriver].
 *
 * The [ai.platon.pulsar.ql.context.SQLContext] interface provided by
 * `pulsar-ql` (published before the package migration) still references
 * this type in its synthetic accessor methods. This interface exists so
 * that those class-file references resolve at runtime without a
 * [NoClassDefFoundError].
 *
 * All new code should use [ai.platon.browser4.api.WebDriver] directly.
 */
interface WebDriver : ai.platon.browser4.api.WebDriver
