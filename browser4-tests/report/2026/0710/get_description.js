var mainContent = document.querySelector('#jobsboard .html') || document.querySelector('.expandContents') || document.querySelector('.job-description');
// Look for the div.html that's inside the expanded job area
var expandedArea = document.querySelector('[data-url*="1133667"]');
var desc = expandedArea ? expandedArea.querySelector('.html, .description, .expandContents') : null;
var text = desc ? desc.innerText : (mainContent ? mainContent.innerText : '');
// Try getting content from the job container that has the most text
var jobContainer = document.querySelector('#job-1133667');
var jobText = jobContainer ? jobContainer.innerText : '';
JSON.stringify({
  hasExpanded: !!expandedArea,
  hasJobContainer: !!jobContainer,
  jobTextLen: jobText.length,
  descLen: text.length,
  text: (jobText || text).substring(0, 4000)
})
