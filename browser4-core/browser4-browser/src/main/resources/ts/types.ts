class MultiStatus {
    /**
     * n: check count
     * scroll: scroll count
     * idl: idle count
     * st: document state
     * r: complete reason
     * ec: error code
     * */
    status =   { n: 0, scroll: 0, idl: 0, st: "", r: "", ec: "" };
    initStat = null;
    lastStat = { w: 0, h: 0, na: 0, ni: 0, nst: 0, nnm: 0};
    lastD =    { w: 0, h: 0, na: 0, ni: 0, nst: 0, nnm: 0};
    initD =    { w: 0, h: 0, na: 0, ni: 0, nst: 0, nnm: 0}
}

class ActiveUrls {
    URL = document.URL;
    baseURI = document.baseURI;
    location = "";
    documentURI = document.documentURI
}

class ActiveDomMessage {
    multiStatus = new MultiStatus();
    urls = new ActiveUrls()
}
