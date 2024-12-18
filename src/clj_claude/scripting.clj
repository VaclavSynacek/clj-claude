(ns clj-claude.scripting
  (:require
   [babashka.curl :as curl]
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.repl :as repl]
   [clojure.string :as str]
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
  [messages & {:keys [anthropic-api-version endpoint model max-tokens api-key system-prompt tools] :as _config}]
  {:headers {"x-api-key"         api-key
             "anthropic-version" anthropic-api-version
             "Accept"            "application/json"
             "anthropic-beta"    "prompt-caching-2024-07-31"}
   :body    {:system    system-prompt
             :model     model
             :tools     tools
             :tool_choice (when tools
                            {:disable_parallel_tool_use true
                             :type :auto})
             :max_tokens max-tokens
             :stream    false
             :messages  (if (vector? messages)
                          messages
                          [messages])}
   :endpoint endpoint})

(defn ->user-messages
  "Wraps a string or [strings] as messages by user (human)"
  [messages-as-strings]
  (if (string? messages-as-strings)
    (->user-messages [messages-as-strings])
    (let [messages {:role "user"
                    :content (->> messages-as-strings
                               (map (fn [m] {:type :text
                                             :text m}))
                               (into []))}]
      (if (:cache (meta messages-as-strings))
        (update-in messages [:content (dec (count messages-as-strings))] assoc :cache_control {:type :ephemeral})
        messages))))
              

(defn send
  "Sends formated request, parses response. Just a wrap around curl and cheeshire"
  [{:keys [endpoint body headers] :as request}]
  (let [response (curl/get
                   endpoint
                   {:headers headers
                    :body (->> body
                               (into {} (remove (comp nil? val)))
                               json/generate-string
                               .getBytes
                               io/input-stream)
                    :throw false})
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

(defn request->request-for-only-token-count
  [{:keys [endpoint body headers] :as _request}]
  {:endpoint (str endpoint "/count_tokens")
   :headers (update-in headers ["anthropic-beta"]
                       #(str/join "," (conj (str/split % #",") "token-counting-2024-11-01")))
   :body (dissoc body :max_tokens :stream)}) 



(defn fn->tool
  "Takes a clojure function with arguments [& {:keys [string1 string2 .. ]}]
   and formats it into tool definition based on the function's docstring"
  [f]
  (let [fn-name (-> f .getClass .getName)
        fn-name (repl/demunge fn-name)
        fn-sym (symbol fn-name)
        m (meta (resolve fn-sym))
        args (:keys (second (first (:arglists m))))]
    {:name         (name (:name m))
     :description  (:doc m)
     :input_schema {:type :object
                    :properties (into {} (map (fn[arg] {(keyword arg) {:type :string}}) args))
                    :required (vec (map keyword args))}}))

(defn fns->tools [fs]
  "Same as fn->tool but for several functions, this one the one to use
   in prompt specification"
  (vec (map fn->tool fs)))

(defn exec-tool [{:keys [id name input]}]
  (log/info :tool-use-execution {:tool name :input input :id id})
  (let [result (eval (read-string (str "(" name " " (pr-str input) ")")))] 
    {:type :tool_result
     :tool_use_id id
     :content result}))

(defn send-with-tools
 "Sends formated request, parses response. If stop_reason = tool_use, than
  executes the tools, updates the request and resends. Until stop_reason != tool_use."
 [{:keys [endpoint body headers] :as request}]
 (let [{:keys [content stop_reason] :as iresult} (send request)]
   (if (= "tool_use" stop_reason)
     (let [the-use (->> content
                        (filter #(= "tool_use" (:type %)))
                        first)
           result  (exec-tool the-use)
           old-messages (:messages body)
           new-messages (vec (concat old-messages
                                     [{:role :assistant :content content}]
                                     [{:role :user :content [result]}]))]
       (recur {:endpoint endpoint
               :headers headers
               :body (assoc body :messages new-messages)}))
     iresult)))



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


  ;; WARNING - this part demonstrates cache that works only from 1000 tokens and above
  ;; WARNING - this may result in MORE COST that the other examples here
  (def cached-part (->user-messages ^:cache [(str "read the following article downloaded from the internet and answer my followup questions. <article>" (slurp "https://paulgraham.com/writes.html") "</article>")]))

  ;; to estimate costs, calculate tokens first, free of charge
  (-> cached-part
      (->request default-config)
      request->request-for-only-token-count
      send)
  ;; => {:input_tokens 3274}  - approx. $0.013 if sent to with cashing enabled
  
  (-> [cached-part
       (->user-messages "As an AI, do you see anything offensive to you in this article?")]
    (->request default-config)
    send)
  ;; => ... :cache_creation_input_tokens 3271 ...

  (-> [cached-part
       (->user-messages "Should I learn to write or to use AI? What is potentially more useful for life in 21st century?")]
    (->request default-config)
    send)
  ;; => ... :input_tokens 27, :cache_creation_input_tokens 0, :cache_read_input_tokens 3271, ...


  ;; Tool use example

  ;; Define plain clojure function
  ;; Currently the it has to accept one argument map and all listed keys
  ;; are required (at least from the Claude point of view)
  ;; Do not forget the docstring, it is passed to Claude so that they know
  ;; how to use the function
  (defn get-weather
   "Gets weather in a city at time, both city and time are required"
   [ & {:keys [city time]}]
   (case city
     "Prague" (str "nice weather at " time)
     "London" "rainy as always"
     "storm is approaching"))

  (get-weather :city "Prague" :time "8AM")
  (get-weather :city "London" :time "8AM")
  (get-weather :city "Paris"  :time "anytime")

  ;; Now run a prompt for which Claude will need to use the above function
  ;; The function is run localy auto-magically
  ;; If this feels unsafe, implement guardrails in the function
  ;; and/or stronger validations on input from Claude
  (-> "What is the weather like in Prague and in London? Where should I want to be now?"
    ->user-messages
    (->request (assoc default-config
                      :tools (fns->tools [get-weather])))
    send-with-tools
    ->first-string
    println)

  comment)


