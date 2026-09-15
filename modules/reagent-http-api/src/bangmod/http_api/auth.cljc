(ns bangmod.http-api.auth
  "How a token gets onto a request. Pure, so the default is unit-tested; the transport
   (`bangmod.http-api.internal`) only calls whichever injector is registered.")

(defn bearer-injector
  "The default injector: `Authorization: Bearer <token>`, unless the call already set an
   `:authorization` header of its own — an explicit header always wins."
  [request token]
  (if (contains? (:headers request) :authorization)
    request
    (assoc-in request [:headers :authorization] (str "Bearer " token))))
