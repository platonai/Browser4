package ai.platon.pulsar.ql.h2.udfs

import ai.platon.pulsar.common.printlnPro
import ai.platon.pulsar.external.ChatModelFactory
import ai.platon.pulsar.heavy.ql.TestBase
import ai.platon.pulsar.ql.h2.utils.ResultSetUtils
import ai.platon.pulsar.test.TestUrls
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.DisplayName

class LLMFunctionsTest : TestBase() {
    val url = TestUrls.PRODUCT_DETAIL_URL
    val sql = """
  select
      llm_extract(dom, 'product name, price, ratings') as llm_extracted_data,
      dom_uri(dom) as url
  from load_and_select('$url', 'body');
        """

    @BeforeEach
    suspend fun setup() {
        Assumptions.assumeTrue(ChatModelFactory.isModelConfigured(session.configuration))
        ensurePage(url)
    }

    /**
     * Test [ai.platon.pulsar.ql.h2.udfs.LLMFunctions.extract]
     * */
    @Test
        @DisplayName("Test extract with field descriptions")
    fun testExtractWithFieldDescriptions() {
        query(sql)
    }

    /**
     * Test [ai.platon.pulsar.ql.h2.udfs.LLMFunctions.extractInternal]
     * */
    @Ignore("Test failed and ignore it temporary")
    @Test
        @DisplayName("Test extract internal with field descriptions and convert to json")
    fun testExtractInternalWithFieldDescriptionsAndConvertToJson() {
        val textContent =
            "Skip to Main content About this item About this item About this item Buying options Compare with similar items Videos Reviews Keyboard shortcuts Search alt + / Cart shift + alt + C Home shift + alt + H Orders shift + alt + O Add to cart shift + alt + K Show/Hide shortcuts shift + alt + Z To move between items, use your keyboard's up or down arrows. .us Deliver to China All Select the department you want to search in All Departments Arts & Crafts Automotive Baby Beauty & Personal Care Books Boys' Fashion Computers Deals Digital Music Electronics Girls' Fashion Health & Household Home & Kitchen Industrial & Scientific Kindle Store Luggage Men's Fashion Movies & TV Music, CDs & Vinyl Pet Supplies Prime Video Software Sports & Outdoors Tools & Home Improvement Toys & Games Video Games Women's Fashion Search Amazon EN Hello, sign in Account & Lists Returns & Orders 0 Cart Sign in New customer? Start here. Your Lists Create a List Find a List or Registry Your Account Account Orders Recommendations Browsing History Watchlist Video Purchases & Rentals Kindle Unlimited Content & Devices Subscribe & Save Items Memberships & Subscriptions Music Library Sign in New customer? Start here. International Shopping Transition Alert We're showing you items that ship to China. To see items that ship to a different country, change your delivery address. Dismiss Change Address All Today's Deals Registry Prime Video Gift Cards Customer Service Sell Disability Customer Support Amazon.com: Huawei P60 Pro Dual SIM 8GB + 256GB Global Model MNA-LX9 Factory Unlocked Mobile Cellphone - Black : Cell Phones & Accessories Cell Phones & Accessories › Cell Phones No featured offers available Learn more No featured offers available We feature offers with an Add to Cart button when an offer meets our high standards for: Quality Price, Reliable delivery option, and Seller who offers good customer service “No featured offers available” means no offers currently meet all of these expectations. Select See All Buying Options to shop available offers. This item cannot be shipped to your selected delivery location. Please choose a different delivery location. Similar items shipping to China CN China See Similar Items Similar items shipping to China Bold K50 | 5G| 2024 | 3-Day Battery | Unlocked | 6.7” FHD+ 3D AMOLED | 256/8GB | Triple 64MP Camera | US Version | US Warranty | PurpleBold K50 | 5G| 2024 | 3-Day Battery | Unlocked | 6.7” FHD+ 3D AMOLED | 256/8GB | Triple 64MP Camera | US Version | US Warranty | Purple REDMAGIC 10 Pro Smartphone 5G, 144Hz Gaming Phone, 6.85\" FHD+, Under Display Camera, 7050mAh Android Phone, Snapdragon 8 Elite, 16+512GB, 80W Charger, Dual-Sim, US Unlocked Cell Phone SilverREDMAGIC 10 Pro Smartphone 5G, 144Hz Gaming Phone, 6.85\" FHD+, Under Display Camera, 7050mAh Android Phone, Snapdragon 8 Elite, 16+512GB, 80W Charger, Dual-Sim, US Unlocked Cell Phone Silver OnePlus 12,16GB RAM+512GB,Dual-SIM,Unlocked Android Smartphone,Supports 50W Wireless Charging,Latest Mobile Processor,Advanced Hasselblad Camera,5400 mAh Battery,2024,Flowy EmeraldOnePlus 12,16GB RAM+512GB,Dual-SIM,Unlocked Android Smartphone,Supports 50W Wireless Charging,Latest Mobile Processor,Advanced Hasselblad Camera,5400 mAh Battery,2024,Flowy Emerald Motorola Edge+ | 2023 | Unlocked | Made for US 8/512 | 50 MPCamera | Intersteller Black, 161.16x74x8.59Motorola Edge+ | 2023 | Unlocked | Made for US 8/512 | 50 MPCamera | Intersteller Black, 161.16x74x8.59 Livinyteb Pura 70 Unlocked Phon 8-core Android 13 Phone 8GB+256GB Unlocked Android Phone 108MP+48MP Camera Pixels 6800mAh Battery 7inch HD LCD Screen Cell Phone 5G Dual SIM (Gold)Livinyteb Pura 70 Unlocked Phon 8-core Android 13 Phone 8GB+256GB Unlocked Android Phone 108MP+48MP Camera Pixels 6800mAh Battery 7inch HD LCD Screen Cell Phone 5G Dual SIM (Gold) Rating 153 reviews 1229 reviews 763 reviews 816 reviews 22 reviews Price \$199.99 \$899.00 \$899.99 \$399.99 \$119.99 Shipping Fee \$11.34 \$11.86 \$12.48 \$11.30 \$11.12 Import Charges \$29.65 \$134.07 \$134.21 \$59.53 \$17.71 Total Estimated Cost \$240.98 \$1,044.93 \$1,046.68 \$470.82 \$148.82 Add to Cart Add to Cart Add to Cart Add to Cart Add to Cart Deliver to China Add to List Added to Unable to add item to List. Please try again. Sorry, there was a problem. There was an error retrieving your Wish Lists. Please try again. Sorry, there was a problem. List unavailable. 6+ 5+ 4+ 3+ 2+ 1+ Unboxing Huawei P60 Pro Ashedits Image Unavailable Image not available for Color: To view this video download Flash Player Roll over image to zoom in VIDEOS 360° VIEW IMAGES Huawei P60 Pro Dual SIM 8GB + 256GB Global Model MNA-LX9 Factory Unlocked Mobile Cellphone - Black Brand: HUAWEI 3.6 3.6 out of 5 stars 36 ratings | Search this page This item cannot be shipped to your selected delivery location. Please choose a different delivery location. Brief content visible, double tap to read full content. Full content visible, double tap to read brief content. Color: Black Make a Color selection 1 option from \$595.00 1 option from \$1,755.00 Brand HUAWEI Operating System EMUI 13 Ram Memory Installed Size 8 GB CPU Speed 2.86 GHz Memory Storage Capacity 8 GB Screen Size 6.67 Inches Resolution 3840 x 2160 Model Name P60 Pro Wireless Carrier Unlocked for All Carriers Cellular Technology 2G, 3G, 4G See more About this item NO Google Services 4G LTE FDD: Bands 1/2/3/4/5/7/8/12/13/17/18/19/20/26/28/32/66 4G LTE TDD: Bands 34/38/39/40/41 3G WCDMA: Bands 1/2/4/5/6/8/19 2G GSM: Bands 2/3/5/8 (850/900/1800/1900 MHz) Rear Camera 48 MP Ultra Lighting Camera (F1.4~F4.0 aperture, OIS) 13 MP Ultra-Wide Angle Camera (F2.2 aperture) 48 MP Ultra Lighting Telephoto Camera (F2.1 aperture, OIS) *The photo pixels may vary depending on the shooting mode. Autofocus Mode Phase Focus, Contrast Focus Zoom Mode 3.5x optical zoom (3.5x zoom is an approximate value, the lens focal lengths are 24.5 mm, 13 mm, 90 mm), and 100x digital zoom. Image Resolution Support up to 8000 × 6000 pixels *The actual image resolution may vary depending on the shooting mode. "
        val result = LLMFunctions.extractInternal(textContent, "product name, price, ratings")

        val expected = mapOf(
            "product_name" to "Huawei P60 Pro Dual SIM 8GB + 256GB Global Model MNA-LX9 Factory Unlocked Mobile Cellphone - Black",
            "price" to "$595.00",
            "ratings" to "3.6 out of 5 stars (36 ratings)"
        )

        assertEquals(expected["product_name"], result["product_name"])
        assertEquals(expected["price"], result["price"])
        assertEquals(expected["ratings"], result["ratings"])
    }

    /**
     * Test [LLMFunctions.extract]
     * */
    @Test
        @DisplayName("Test extract with field descriptions and convert to json")
    fun testExtractWithFieldDescriptionsAndConvertToJson() {
        query(sql) {
            val json = ResultSetUtils.toJson(it, prettyPrinting = true)
            printlnPro(json)
        }
    }
}
