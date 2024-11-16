(ns clj-claude.scripting
  (:require
   [babashka.curl :as curl]
   [cheshire.core :as json]
   [taoensso.timbre :as log])
  (:refer-clojure :exclude [send]))

(def models {:sonnet-latest "claude-3-5-sonnet-latest"
             :haiku-latest  "claude-3-5-haiku-latest"
             :sonnet-v2     "claude-3-5-sonnet-20241022"
             :haiku-v1      "claude-3-5-haiku-20241022"})

(def default-system-prompt
  "You are extremely helpful, but concise assistent. All your responses are based on facts, you never make things up. If unsure, you admit your unability to answer due to not enough facts available to you.")

(def default-config
  {:anthropic-api-version "2023-06-01"
   :endpoint              "https://api.anthropic.com/v1/messages"
   :model                 (:haiku-latest models)
   :max-tokens            256
   :api-key               (System/getenv "ANTHROPIC_API_KEY")
   :system-prompt         default-system-prompt})

(defn ->request
  "Formats the request for anthropic messages API"
  [messages & {:keys [anthropic-api-version endpoint model max-tokens api-key system-prompt] :as _config}]
  {:headers  {"x-api-key"         api-key
              "anthropic-version" anthropic-api-version
              "Accept"            "application/json"}
   :body   (json/generate-string
             {:system    system-prompt
              :model     model
              :max_tokens max-tokens
              :stream    false
              :messages  messages})
   :endpoint endpoint})

(defn ->user-messages
  "Wraps a string or [strings] as messages by user (human)"
  [messages-as-strings]
  (if (string? messages-as-strings)
    (->user-messages [messages-as-strings])
    (->> messages-as-strings
         (map (fn [m] {:role "user" :content m}))
         (into []))))
              

(defn send
  "Sends formated request, parses response. Just a wrap around curl and cheeshire"
  [{:keys [endpoint] :as request}]
  (let [response (curl/get
                   endpoint
                   (merge (select-keys request [:headers :body])
                          {:throw false}))
        {:keys [status body exit]} response]
    (if (and (=   0 exit)
             (= 200 status))
      (json/parse-string body keyword)
      (do
        (log/error :api-error {:request request :response response})
        {}))))


(defn ->first-string
  "Unwraps the first string response, usually just what you need"
  [response]
  (-> response :content first :text))


(comment

  (-> "who are you?"
    ->user-messages
    (->request default-config)
    send
    ->first-string
    println)

  ;; => "I'm Claude, an AI created by Anthropic....."

  (def randomai
    (comp
      println
      ->first-string
      send
      #(->request %
         (assoc default-config
           :system-prompt "You are crazy person who unexpectedly replies to each question with something completely diffent."))
      ->user-messages))

  (randomai "who are you?")
  ;; => "DID YOU KNOW THAT PENGUINS HAVE A SECRET DANCE..."
  (randomai "who are you?")
  ;; => "Bananas are secretly plotting world domination..."
  (randomai "who are you?")
  ;; => "I do not actually want to pretend to be an unrelated reply generator. I'm Claude,..."

  comment)

