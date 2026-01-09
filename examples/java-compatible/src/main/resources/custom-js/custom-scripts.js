"use strict";

let layerStyle = `<style>
#customDiv .openBtn {
display: flex;
justify-content: left;
}
#customDiv .openButton {
border: none;
border-radius: 5px;
background-color: #1c87c9;
color: white;
padding: 14px 20px;
cursor: pointer;
position: fixed;
}
#customDiv .loginPopup {
position: relative;
text-align: center;
width: 100%;
}
#customDiv .formPopup {
display: none;
position: fixed;
left: 45%;
top: 5%;
transform: translate(-50%, 5%);
border: 3px solid #999999;
z-index: 9;
}
#customDiv .formContainer {
max-width: 300px;
padding: 20px;
background-color: #fff;
}
#customDiv .formContainer input[type=text],
#customDiv .formContainer input[type=password] {
width: 100%;
padding: 15px;
margin: 5px 0 20px 0;
border: none;
background: #eee;
}
#customDiv .formContainer input[type=text]:focus,
#customDiv .formContainer input[type=password]:focus {
background-color: #ddd;
outline: none;
}
#customDiv .formContainer .btn {
padding: 12px 20px;
border: none;
background-color: #8ebf42;
color: #fff;
cursor: pointer;
width: 100%;
margin-bottom: 15px;
opacity: 0.8;
}
#customDiv .formContainer .cancel {
background-color: #cc0000;
}
#customDiv .formContainer .btn:hover,
#customDiv .openButton:hover {
opacity: 1;
}
</style>`;

let popupCode = `
<div id="customDiv">
    <div class="loginPopup">
      <div class="formPopup" id="popupForm">
        <form action="" class="formContainer">
          <h2>Injected script - Please NOT Log in</h2>
          <label for="email">
            <strong>E-mail</strong>
          </label>
          <input type="text" id="email" placeholder="Your Email" name="email" required>
          <label for="psw">
            <strong>Password</strong>
          </label>
          <input type="password" id="psw" placeholder="Your Password" name="psw" required>
          <button type="submit" class="btn">Log in</button>
          <button type="button" class="btn cancel">Close</button>
        </form>
      </div>
    </div>
</div>
`

let __custom_utils__ = function () {
};

__custom_utils__.minus = function(a, b) {
    return a - b
};

__custom_utils__.openForm = function() {
    document.getElementById("popupForm").style.display = "block";
}

__custom_utils__.closeForm = function() {
    document.getElementById("popupForm").style.display = "none";
}

__custom_utils__.addCustomEventListeners = function() {
    document.head.insertAdjacentHTML("beforeend", layerStyle)
    document.querySelector('body div').insertAdjacentHTML('beforeend', popupCode)
    __custom_utils__.openForm()

    document.querySelectorAll('div').forEach(div => {
        div.addEventListener('click', ev => {
            let message = "path: " + div.id + div.className
                + "vi: " + div.getAttribute("vi")
            console.log(message)

            // let info = document.createElement("div")
            // info.style.display = "fixed"
            // info.scrollLeft = div.scrollLeft
            // info.scrollLeft = div.scrollTop + div.clientHeight
            // info.clientWidth = 100
            // info.clientHeight = 30
            // info.textContent = message
            // div.appendChild(info)
        })
    })
};
