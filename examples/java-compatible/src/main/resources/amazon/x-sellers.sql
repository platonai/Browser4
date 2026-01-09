-- noinspection SqlResolveForFile
-- noinspection SqlNoDataSourceInspectionForFile

select
    dom_base_uri(dom) as `url`,
    str_substring_between(dom_base_uri(dom), 'seller=', '&') as `sellerID`,
    dom_first_text(dom, '#seller-summary h1#sellerName') as `seller_name`,
    dom_base_uri(dom) as `seller_url`,
    str_substring_between(dom_base_uri(dom), 'marketplaceID=', '&') as `marketplaceID`,
    dom_first_text(dom, '#seller-feedback-summary span a') as `feedbackSummary`,
    dom_first_text(dom, 'ul li:has(span:contains(Business Name))') as `business_name`,
    dom_first_text(dom, 'ul li:has(span:contains(Business Address))') as `business_address`,
    cast(dom_all_texts(dom, '#feedback-content #feedback-summary-table tr:contains(Positive) td') as varchar) as `highstarpercent`,
    cast(dom_all_texts(dom, '#feedback-content #feedback-summary-table tr:contains(Neutral) td') as varchar) as `middlestarpercent`,
    cast(dom_all_texts(dom, '#feedback-content #feedback-summary-table tr:contains(Negative) td') as varchar) as `badstarpercent`,
    cast(dom_all_texts(dom, '#feedback-content #feedback-summary-table tr:contains(Count) td') as varchar) as `feedback_num_12`,
    cast(dom_all_texts(dom, '#feedback-content #feedback-summary-table tr:contains(Count) td') as varchar) as `feedback_num`,
    dom_attr(dom_select_first(dom_owner_body(dom), '#PulsarMetaInformation'), 'label') as `label`,
    time_first_mysql_date_time(dom_attr(dom_select_first(dom_owner_body(dom), '#PulsarMetaInformation'), 'taskTime')) as `task_time`
from load_and_select(@url, ':root body');
