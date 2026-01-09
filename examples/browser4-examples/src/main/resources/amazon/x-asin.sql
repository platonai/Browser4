-- noinspection SqlDialectInspectionForFile
-- noinspection SqlResolveForFile
-- noinspection SqlNoDataSourceInspectionForFile

select
    dom_uri(dom) as `url`,
    dom_base_uri(dom) as `base_Uri`,
    dom_first_text(dom, '#productTitle') as `title`,

    -- category
    str_substring_after(dom_first_href(dom, '#wayfinding-breadcrumbs_container ul li:last-child a'), '&node=') as `category_node_id`,
    cast(dom_all_hrefs(dom, '#wayfinding-breadcrumbs_container ul li a') as varchar) as `category_link_path`,
    dom_first_text(dom, '#wayfinding-breadcrumbs_container ul li:last-child a') as `category_name`,
    array_join_to_string(dom_all_texts(dom, '#wayfinding-breadcrumbs_container ul li a'), ' -:> ') as `category_name_path`,

    -- Q.A.
    str_first_integer(dom_first_text(dom, '#askATFLink, .askTopQandALoadMoreQuestions a'), 0) as `qa_num`,
    -- Reviews
    str_first_integer(dom_first_text(dom, '#acrCustomerReviewText, #reviewsMedley div[data-hook=total-review-count] span, #reviewsMedley span:contains(ratings), #reviewsMedley span:contains(customer ratings)'), 0) as `reviews`,

    -- brand
    dom_first_minimal_html(dom, 'div#centerCol a#bylineInfo') as `brand_html`,
    dom_first_attr(dom, 'div#centerCol a#bylineInfo', 'abs:href') as `brand_link`,
    dom_first_text(dom, 'div#centerCol a#bylineInfo') as `brand`,

    -- price
    dom_first_minimal_html(dom, '#price, #apex_desktop, div[data-feature-name=apex_desktop]') as `price_info_html`,
    dom_first_text(dom, '#price, #apex_desktop, div[data-feature-name=apex_desktop]') as `price_info_text`,
    dom_first_text(dom, '#apex_desktop span.basisPrice:contains(List Price)') as `list_price_info_text`,
    dom_all_texts(dom, '#apex_desktop span.basisPrice:contains(List Price) span:containsOwn($)') as `list_price`,
    dom_first_text(dom, '#apex_desktop .priceToPay span, #price tr td:matches(^Price) ~ td') as `price`,
    dom_first_text(dom, '#priceblock_dealprice, #price tr td:contains(Deal of the Day) ~ td, #apex_desktop tr td:contains(Deal of the Day) ~ td') as `with_deal`,
    dom_first_text(dom, '#apex_desktop .savingsPercentage, #dealprice_savings .priceBlockSavingsString, #price tr td:contains(You Save) ~ td') as `you_save`,

    -- product images
    dom_first_minimal_html(dom, '#landingImage, #imgTagWrapperId img, #imageBlock img:expr(width>400)') as `main_img`,
    dom_first_attr(dom, '#landingImage, #imgTagWrapperId img, #imageBlock img:expr(width>400)', 'data-old-hires') as `main_img_src`,
    dom_first_attr(dom, '#landingImage, #imgTagWrapperId img, #imageBlock img:expr(width>400)', 'data-a-dynamic-image') as `dynamic_img_srcs`,
    cast(dom_all_minimal_htmls(dom, '#imageBlock img') as varchar) as `gallery_html`,
    cast(dom_all_attrs(dom, '#imageBlock img', 'src') as varchar) as `gallery_links`,

    -- buy box
    dom_first_minimal_html(dom, ' #buybox, #desktop_buybox, #qualifiedBuybox, div[data-feature-name=desktop_buybox]') as `buy_box_html`,
    dom_first_text(dom, '#buybox, #desktop_buybox, #qualifiedBuybox, div[data-feature-name=desktop_buybox]') as `buy_box_text`,

    dom_first_text(dom, ' #qualifiedBuybox .a-price,' ||
                        ' #corePrice_feature_div,' ||
                        ' div[data-feature-name=corePrice]') as `buy_box_price`,

    -- seller info
    dom_all_texts(dom, ' a#sellerProfileTriggerId[href~=seller],' ||
                       ' #tabular-buybox tr:has(td:contains(Sold by)) td a[href~=seller],' ||
                       ' #usedbuyBox div:contains(Sold by) a[href~=seller],' ||
                       ' #merchant-info a[href~=seller],' ||
                       ' #tabular-buybox a[href~=seller],' ||
                       ' #buybox-tabular a[href~=seller]' ||
                       ' div.tabular-buybox-text[tabular-attribute-name*="Sold by"]'
        ) as `buy_box_sold_by`,
    dom_all_hrefs(dom, ' a#sellerProfileTriggerId[href~=seller],' ||
                       ' #tabular-buybox tr:has(td:contains(Sold by)) td a[href~=seller],' ||
                       ' #usedbuyBox div:contains(Sold by) a[href~=seller],' ||
                       ' #merchant-info a[href~=seller],' ||
                       ' #buybox-tabular a[href~=seller]'
        ) as `buy_box_seller_id`,
    dom_all_hrefs(dom, ' a#sellerProfileTriggerId[href~=seller],' ||
                       ' #tabular-buybox tr:has(td:contains(Sold by)) td a[href~=seller],' ||
                       ' #usedbuyBox div:contains(Sold by) a[href~=seller],' ||
                       ' #merchant-info a[href~=seller],' ||
                       ' #buybox-tabular a[href~=seller]'
        ) as `buy_box_marketplace_id`,
    dom_all_texts(dom, ' #tabular-buybox div.tabular-buybox-text[tabular-attribute-name=Ships from],' ||
                       ' #tabular-buybox tr:has(td:contains(Ships from)) td,' ||
                       ' #buybox-tabular tr:has(td:contains(Ships from)) td' ||
                       ' div.tabular-buybox-text[tabular-attribute-name*="Ships from"]'
        ) as `buy_box_ships_from`,
    dom_all_texts(dom, ' #tabular-buybox div.tabular-buybox-text[tabular-attribute-name=Dispatches from],' ||
                       ' #tabular-buybox tr:has(td:contains(Dispatches from)) td,' ||
                       ' #buybox-tabular tr:has(td:contains(Dispatches from)) td' ||
                       ' div.tabular-buybox-text[tabular-attribute-name=Dispatches from]'
        ) as `buy_box_dispatches_from`,

    -- other sellers, other sellers shows if `New (X) Seller` anchor clicked
    dom_first_text(dom, '#olp-upd-new-used a, #olp-upd-new a, .olp-text-box span') as `other_seller_text`,
    str_substring_between(dom_first_text(dom, '#olp-upd-new-used a, #olp-upd-new a, .olp-text-box span'), '(', ')') as `other_seller_num`,
    dom_all_hrefs(dom, '#aod-offer a[href*=seller]') as `other_seller_urls`,

    -- variations
    dom_first_minimal_html(dom, '#twister_feature_div, div[data-feature-name=twister], #variation_color_name') as `variations_html`,
    dom_first_text(dom, '#twister_feature_div, div[data-feature-name=twister], #variation_color_name') as `variations_text`,

    -- special flags
    str_is_not_empty(dom_first_text(dom, '#acBadge_feature_div i:contains(Best Seller)')) as `is_best_seller`,
    str_is_not_empty(dom_first_text(dom, '#acBadge_feature_div span:contains(Amazon),' ||
                                         ' div[data-feature-name=acBadge] span:contains(Amazon)')) as `is_amazon_choice`,
    str_is_not_empty(dom_first_text(dom, '#centerCol #couponBadgeRegularVpc')) as `is_coupon`,
    dom_first_text(dom, '#centerCol div i:contains(Prime Day Deal)') as `is_prime`,

    dom_first_text(dom, '#glow-ingress-block') as `global_deliver_to`,
    dom_first_text(dom, '#contextualIngressPtLabel_deliveryShortLine,' ||
                        ' #glowContextualIngressPt_feature_div,' ||
                        ' div[data-feature-name=glowContextualIngressPt]') as `deliver_to`,
    to_json(map(
       'globaldeliverto', dom_first_text(dom, '#glow-ingress-block'),
       'deliverto', dom_first_text(dom, '#contextualIngressPtLabel_deliveryShortLine,' ||
                                        ' #glowContextualIngressPt_feature_div,' ||
                                        ' div[data-feature-name=glowContextualIngressPt]')
    )) as `combined_deliver_to`,

    str_abbreviate(dom_first_text(dom, '#availability, #outOfStock'), 1024) as `in_stock`,
    dom_first_text(dom, '#selectQuantity select option:last-child') as `quantity`,
    dom_all_hrefs(dom, ' #availability a,' ||
                       ' #olp-upd-new-used a,' ||
                       ' #olp-upd-new a,' ||
                       ' #certified-refurbished-version a[href~=/dp/],' ||
                       ' #newer-version a[href~=/dp/]') as `sell_same_url`,

    array_join_to_string(dom_all_attrs(dom, '#sims-fbt #sims-fbt-content ul li[data-p13n-asin-metadata]', 'data-p13n-asin-metadata'), '^|^') as `bought_together_metadata`,
    array_join_to_string(dom_all_attrs(dom, '#sims-fbt #sims-fbt-content ul li[class~=sims-fbt-image] img', 'src'), '^|^') as `bought_to_gether_imgs`,
    array_join_to_string(dom_all_texts(dom, '#sims-fbt #sims-fbt-content ul li[data-p13n-asin-metadata]'), '^|^') as `bought_together`,

    str_is_not_empty(dom_first_text(dom, '#addToCart_feature_div span:contains(Add to Cart), #submit.add-to-cart-ubb-announce')) as `has_add_cart`,
    str_is_not_empty(dom_first_text(dom, '#buyNow span:contains(Buy now)')) as `has_buy`,

    -- product overview
    cast(dom_all_minimal_htmls(dom, '#productOverview_feature_div table,' ||
                                    ' div[data-feature-name=productOverview] table') as varchar) as `product_overview_html`,
    cast(dom_all_texts(dom, '#productOverview_feature_div table tr,' ||
                            ' div[data-feature-name=productOverview] table tr') as varchar) as `product_overview`,
    dom_first_text(dom, '#productOverview_feature_div table tr td:contains(Brand) ~ td,' ||
                        ' div[data-feature-name=productOverview] table tr td:contains(Brand) ~ td') as `product_overview_brand`,

    -- feature bullets
    cast(dom_all_minimal_htmls(dom, '#feature-bullets ul li, div[data-feature-name=featurebullets] ul li') as varchar) as `feature_bullets_html`,
    cast(dom_all_texts(dom, '#feature-bullets ul li, div[data-feature-name=featurebullets] ul li') as varchar) as `feature_bullets`,

    -- product information
    -- technical details
    dom_first_minimal_html(dom, ' table#productDetails_techSpec_section_1,' ||
                                ' table.prodDetTable') as `technical_details_html`,
    dom_first_text(dom, ' table#productDetails_techSpec_section_1,' ||
                        ' table.prodDetTable') as `technical_details_text`,
    dom_all_texts(dom, ' table#productDetails_techSpec_section_1 tr,' ||
                       ' table.prodDetTable tr') as `technical_details`,
    dom_first_text(dom, ' table#productDetails_techSpec_section_1 tr th:contains(Brand) ~ td,' ||
                        ' table.prodDetTable tr th:contains(Brand) ~ td') as `technical_details_brand`,
    dom_first_text(dom, ' table#productDetails_techSpec_section_1 tr th:contains(Manufacturer) ~ td,' ||
                        ' table.prodDetTable tr th:contains(Manufacturer) ~ td') as `technical_details_manufacturer`,
    dom_first_text(dom, ' #prodDetails table tr > th:contains(Product Dimensions) ~ td,' ||
                        ' #detailBullets_feature_div ul li span:contains(Package Dimensions) ~ span') as `technical_details_detail_volume`,
    dom_first_text(dom, ' #prodDetails table tr > th:contains(Item Weight) ~ td') as `technical_details_detail_weight`,

    -- additional information
    dom_first_minimal_html(dom, ' table#productDetails_detailBullets_sections1,' ||
                                ' #productDetails_db_sections table.prodDetTable') as `additional_info_html`,
    dom_first_text(dom, ' table#productDetails_detailBullets_sections1,' ||
                        ' #productDetails_db_sections table.prodDetTable') as `additional_info_text`,
    dom_all_texts(dom, ' table#productDetails_detailBullets_sections1 tr,' ||
                       ' #productDetails_db_sections table.prodDetTable tr') as `additional_info`,
    dom_first_text(dom, ' table#productDetails_detailBullets_sections1 tr th:contains(ASIN) ~ td,' ||
                        ' #productDetails_db_sections table.prodDetTable tr th:contains(ASIN) ~ td') as `additional_info_asin`,
    dom_first_minimal_html(dom, ' #prodDetails table tr > th:contains(Best Sellers Rank) ~ td,' ||
                                ' #detailBullets_feature_div ul li span:contains(Best Sellers Rank)') as `additional_info_rank_html`,
    dom_first_text(dom, ' #prodDetails table tr > th:contains(Best Sellers Rank) ~ td,' ||
                        ' #detailBullets_feature_div ul li span:contains(Best Sellers Rank)') as `additional_info_rank_text`,
    dom_first_text(dom, ' #prodDetails table tr > th:contains(Date First) ~ td,' ||
                        ' #detailBullets_feature_div ul li span:contains(Date First Available) ~ span') as `additional_info_on_sale_time`,

    cast(dom_all_attrs(dom, ' #prodDetails img[src], #productDescription img[src],' ||
                            ' #dpx-aplus-product-description_feature_div img[src],' ||
                            ' #dpx-aplus-3p-product-description_feature_div img[src]', 'src') as varchar) as `detail_imgs`,
    cast(dom_all_hrefs(dom, '#rvs-vse-related-videos ol li a[href~=/vdp/]') as varchar) as `detail_videos`,

    dom_first_text(dom, ' #prodDetails table tr > th:contains(ASIN) ~ td,' ||
                        ' #detailBullets_feature_div ul li span:contains(ASIN) ~ span') as `detail_asin`,
    cast(dom_all_minimal_htmls(dom, '#prodDetails h1:contains(Feedback) ~ div a') as varchar) as `feedback_url_html`,
    dom_all_attrs(dom, '#prodDetails h1:contains(Feedback) ~ div a', 'abs:src') as `feedback_urls`,

    -- description
    dom_first_minimal_html(dom, 'h2:contains(Product Description) ~ div') as `product_description_html`,
    dom_first_text(dom, 'h2:contains(Product Description) ~ div') as `product_description_text`,
    dom_all_attrs(dom, 'h2:contains(Product Description) ~ div img', 'abs:src') as `product_description_images`,

    array_length(dom_all_imgs(dom, '#prodDetails img[src], #productDescription img[src]')) as `is_a_plus`,

    -- From the brand (A+ Brand Story)
    dom_first_minimal_html(dom, ' #aplusBrandStory_feature_div,' ||
                                ' div[data-feature-name=aplusBrandStory]') as `brand_story_html`,
    dom_first_text(dom, ' #aplusBrandStory_feature_div,' ||
                        ' div[data-feature-name=aplusBrandStory]') as `brand_story_text`,
    dom_all_minimal_htmls(dom, ' #aplusBrandStory_feature_div .apm-brand-story-card a[href~=/dp/],' ||
                               ' div[data-feature-name=aplusBrandStory] .apm-brand-story-card a[href~=/dp/]') as `brand_story_product_htmls`,
    dom_all_attrs(dom, ' #aplusBrandStory_feature_div .apm-brand-story-card a[href~=/dp/] img,' ||
                       ' div[data-feature-name=aplusBrandStory] .apm-brand-story-card a[href~=/dp/] img', 'alt') as `brand_story_product_names`,
    dom_all_attrs(dom, ' #aplusBrandStory_feature_div .apm-brand-story-card a[href~=/dp/],' ||
                       ' div[data-feature-name=aplusBrandStory] .apm-brand-story-card a[href~=/dp/]', 'abs:href') as `brand_story_product_links`,
    dom_all_attrs(dom, ' #aplusBrandStory_feature_div .apm-brand-story-card a[href~=/dp/] img,' ||
                       ' div[data-feature-name=aplusBrandStory] .apm-brand-story-card a[href~=/dp/] img', 'abs:src') as `brand_story_product_images`,

    cast(dom_all_texts(dom, '#reviewsMedley div[data-hook=lighthut-terms-list] a, #reviewsMedley h3:contains(Read reviews that mention) ~ div a') as varchar) as `reviews_mention`,
    str_first_float(dom_first_text(dom, '#reviewsMedley .AverageCustomerReviews span:contains(out of)'), 0.0) as `score`,
    str_first_integer(dom_first_text(dom, '#reviewsMedley div[data-hook=total-review-count], #acrCustomerReviewText'), 0) as `star_num`,
    dom_first_text(dom, 'table#histogramTable:expr(width > 100) td:contains(5 star) ~ td:contains(%)') as `score_5_percent`,
    dom_first_text(dom, 'table#histogramTable:expr(width > 100) td:contains(4 star) ~ td:contains(%)') as `score_4_percent`,
    dom_first_text(dom, 'table#histogramTable:expr(width > 100) td:contains(3 star) ~ td:contains(%)') as `score_3_percent`,
    dom_first_text(dom, 'table#histogramTable:expr(width > 100) td:contains(2 star) ~ td:contains(%)') as `score_2_percent`,
    dom_first_text(dom, 'table#histogramTable:expr(width > 100) td:contains(1 star) ~ td:contains(%)') as `score_1_percent`,
    dom_all_texts(dom, 'div#cr-dp-summarization-attributes div[data-hook=cr-summarization-attribute]') as `scores_by_feature`,
    dom_first_href(dom, '#reviews-medley-footer a') as `reviews_url`,

    dom_first_attr(dom_owner_document(dom), 'head meta[name=keywords]', 'content') as `meta_keywords`,
    dom_first_attr(dom_owner_document(dom), 'head meta[name=description]', 'content') as `meta_description`,

    -- variables from javascript
    dom_first_text(dom_owner_body(dom), '#pulsarJsVariables pre.jsVariables') as `js_variables`,
    dom_first_text(dom_owner_body(dom), '#pulsarJsVariables pre.parentAsin') as `js_pasin`,
    dom_first_text(dom_owner_body(dom), '#pulsarJsVariables pre.variationValues') as `js_variation_values`,
    dom_first_text(dom_owner_body(dom), '#pulsarJsVariables pre.asinVariationValues') as `js_asin_variation_values`,
    dom_first_text(dom_owner_body(dom), '#pulsarJsVariables pre.num_total_variations') as `js_num_total_variations`,

    -- variables from input
    dom_all_attrs(dom, 'input[type=hidden]', 'name') as `input_names`,
    dom_all_attrs(dom, 'input[type=hidden]', 'value') as `input_values`,
    dom_all_multi_attrs(dom, 'input[type=hidden]', make_array('name', 'value')) as `input_properties`,
    dom_first_attr(dom, 'input[name=asin]', 'value') as `input_asin`,
    dom_first_attr(dom, 'input[name=marketplaceId]', 'value') as `input_marketplace_id`,
    dom_first_attr(dom, 'input[name=productTitle]', 'value') as `input_product_title`,
    dom_first_attr(dom, 'input[name=priceSymbol]', 'value') as `input_price_symbol`,
    dom_first_attr(dom, 'input[name=priceValue]', 'value') as `input_price_value`,
    dom_first_attr(dom, 'input[name=productImageUrl]', 'value') as `input_product_image_url`,

    -- variables from metadata
    dom_first_attr(dom, '#PulsarMetaInformation', 'href') as `meta_href`,
    dom_first_attr(dom, '#PulsarMetaInformation', 'referer') as `meta_referer`,
    dom_first_attr(dom, '#PulsarMetaInformation', 'referrer') as `meta_referrer`,
    dom_first_attr(dom, '#PulsarMetaInformation', 'label') as `meta_label`,
    time_first_mysql_date_time(dom_attr(dom_select_first(dom_owner_body(dom), '#PulsarMetaInformation'), 'taskTime')) as `meta_task_time`,

    -- all feature names defined by amazon
    dom_all_attrs(dom, '*[data-feature-name]', 'data-feature-name') as `amazon_feature_names`,

    -- dom features calculated by Browser4
    dom_ch(dom_owner_body(dom)) as `pulsar_num_chars`,
    dom_a(dom_owner_body(dom)) as `pulsar_num_links`,
    dom_img(dom_owner_body(dom)) as `pulsar_num_imgs`,
    dom_height(dom_owner_body(dom)) as `pulsar_height`
from load_and_select(@url, ':root body');
