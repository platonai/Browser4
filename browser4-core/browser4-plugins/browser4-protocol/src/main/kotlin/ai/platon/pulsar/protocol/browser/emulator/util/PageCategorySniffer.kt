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
package ai.platon.pulsar.protocol.browser.emulator.util

import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.persist.PageDatum
import ai.platon.pulsar.persist.metadata.OpenPageCategory
import ai.platon.pulsar.persist.metadata.PageCategory
import java.util.concurrent.CopyOnWriteArrayList


interface PageCategorySniffer {
    operator fun invoke(pageDatum: PageDatum): OpenPageCategory
}

open class DefaultPageCategorySniffer(val conf: ImmutableConfig): PageCategorySniffer {
    override fun invoke(pageDatum: PageDatum): OpenPageCategory {
        return OpenPageCategory(PageCategory.UNKNOWN)
    }
}

class ChainedPageCategorySniffer(val conf: ImmutableConfig): PageCategorySniffer {
    private val sniffers = CopyOnWriteArrayList<PageCategorySniffer>()

    override fun invoke(pageDatum: PageDatum): OpenPageCategory {
        for (sniffer in sniffers) {
            val category = sniffer(pageDatum)
            if (category.toPageCategory() != PageCategory.UNKNOWN) {
                return category
            }
        }

        return OpenPageCategory(PageCategory.UNKNOWN)
    }

    fun addFirst(sniffer: PageCategorySniffer): ChainedPageCategorySniffer {
        sniffers.add(0, sniffer)
        return this
    }

    fun addLast(sniffer: PageCategorySniffer): ChainedPageCategorySniffer {
        sniffers.add(sniffer)
        return this
    }

    fun remove(sniffer: PageCategorySniffer) {
        sniffers.remove(sniffer)
    }
}
