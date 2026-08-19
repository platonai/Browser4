/**
 * detectForms.js — forms plugin (Detect forms, their fields and submit buttons on the current page)
 *
 * Runs inside the browser page (via WebDriver.evaluateValue / tab.eval).
 * Returns a plain object serialized as JSON.
 */
(function () {
  'use strict';

  function normalizeType(el) {
    return (el.getAttribute('type') || '').toLowerCase();
  }

  function collectInputTypes(inputs) {
    var counts = {};
    Array.prototype.forEach.call(inputs, function (input) {
      var type = normalizeType(input) || 'text';
      counts[type] = (counts[type] || 0) + 1;
    });
    return counts;
  }

  var allInputs = document.querySelectorAll('input');
  var allSelects = document.querySelectorAll('select');
  var allTextareas = document.querySelectorAll('textarea');
  var allButtons = document.querySelectorAll('button');

  var allPasswordFields = 0;
  var allSubmitButtons = 0;
  Array.prototype.forEach.call(allInputs, function (input) {
    var type = normalizeType(input);
    if (type === 'password') {
      allPasswordFields++;
    }
    if (type === 'submit' || type === 'image') {
      allSubmitButtons++;
    }
  });
  Array.prototype.forEach.call(allButtons, function (button) {
    var type = normalizeType(button);
    if (button.form && (type === '' || type === 'submit')) {
      allSubmitButtons++;
    }
  });

  var forms = Array.prototype.slice.call(document.querySelectorAll('form'));
  var formDetails = forms.map(function (form) {
    var inputs = form.querySelectorAll('input');
    var selects = form.querySelectorAll('select');
    var textareas = form.querySelectorAll('textarea');
    var buttons = form.querySelectorAll('button');

    var passwordFields = 0;
    Array.prototype.forEach.call(inputs, function (input) {
      if (normalizeType(input) === 'password') {
        passwordFields++;
      }
    });

    var submitButtons = 0;
    Array.prototype.forEach.call(inputs, function (input) {
      var type = normalizeType(input);
      if (type === 'submit' || type === 'image') {
        submitButtons++;
      }
    });
    Array.prototype.forEach.call(buttons, function (button) {
      var type = normalizeType(button);
      if (type === '' || type === 'submit') {
        submitButtons++;
      }
    });

    return {
      action: form.getAttribute('action') || '',
      fieldCount: inputs.length + selects.length + textareas.length,
      inputs: inputs.length,
      selects: selects.length,
      textareas: textareas.length,
      buttons: buttons.length,
      inputTypes: collectInputTypes(inputs),
      hasPasswordField: passwordFields > 0,
      hasSubmitButton: submitButtons > 0
    };
  });

  var result = {
    url: location.href,
    data: {
      totalForms: forms.length,
      totals: {
        inputs: allInputs.length,
        selects: allSelects.length,
        textareas: allTextareas.length,
        buttons: allButtons.length,
        inputTypes: collectInputTypes(allInputs),
        passwordFields: allPasswordFields,
        submitButtons: allSubmitButtons
      },
      forms: formDetails
    }
  };

  return JSON.stringify(result, null, 2);
})();
