(ns ring.middleware.anti-forgery.signed-token
  (:require [ring.middleware.anti-forgery.strategy :as strategy]
            [clj-time.core :as time]
            [clj-time.coerce]
            [buddy.sign.jwt :as jwt]
            [crypto.equality :as crypto]
            [clojure.tools.logging :as log])
  (:import (clojure.lang ExceptionInfo)))

(def ^:private crypt-options {:alg :rs512})

(deftype SignedTokenStrategy [public-key private-key expiration-period get-subject-fn]

  strategy/Strategy

  (get-token [_ request]
    (delay (let [claims {;; Issued at (see https://tools.ietf.org/html/rfc7519#section-4.1.6)
                         :iat (clj-time.coerce/to-epoch (time/now))

                         ;; Expires (see https://tools.ietf.org/html/rfc7519#section-4.1.4)
                         :exp (clj-time.coerce/to-epoch (time/plus (time/now) expiration-period))}
                 claims-with-optional-subject (if-let [subject (get-subject-fn request)]
                                                (assoc claims :sub subject)
                                                claims)]
             (jwt/sign claims-with-optional-subject
                       private-key
                       crypt-options))))

  (valid-token? [_ request token]
    (try
      (let [{:keys [sub]} (jwt/unsign token
                                      public-key
                                      crypt-options)]

        ;; check subject (must either be empty (now, not at token claim) or equal to the one in the claims)
        (when-let [subject (get-subject-fn request)]
          (when-not (crypto/eq? sub subject)
            (throw (ex-info (str "Subject does not match " sub)
                            {:type :validation :cause :sub}))))
        true)
      (catch ExceptionInfo e
        (when-not (= (:type (ex-data e)) :validation)
          (throw e))
        (when-not (= (:cause (ex-data e)) :exp)
          (log/warn
            e
            "Security warning: Potential CSRF-Attack"
            (ex-data e)))
        false)))

  (write-token [_ _ response _]
    response))

(defn signed-token [public-key private-key expiration-period get-subject-fn]
  (->SignedTokenStrategy public-key private-key expiration-period get-subject-fn))
