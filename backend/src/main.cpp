#include <iostream>
#include <websocketpp/config/asio_no_tls.hpp>
#include <websocketpp/server.hpp>
#include <nlohmann/json.hpp>
#include <thread>

typedef websocketpp::server<websocketpp::config::asio> server;
using json = nlohmann::json;

class LaserEngine {
public:
    LaserEngine() {
        m_server.init_asio();

        // Register handlers
        m_server.set_open_handler(bind(&LaserEngine::on_open, this, std::placeholders::_1));
        m_server.set_close_handler(bind(&LaserEngine::on_close, this, std::placeholders::_1));
        m_server.set_message_handler(bind(&LaserEngine::on_message, this, std::placeholders::_1, std::placeholders::_2));
    }

    void run(uint16_t port) {
        m_server.listen(port);
        m_server.start_accept();
        std::cout << "LaserEngine control socket listening on port " << port << std::endl;
        m_server.run();
    }

private:
    server m_server;

    void on_open(websocketpp::connection_hdl hdl) {
        std::cout << "Client connected!" << std::endl;
    }

    void on_close(websocketpp::connection_hdl hdl) {
        std::cout << "Client disconnected!" << std::endl;
    }

    void on_message(websocketpp::connection_hdl hdl, server::message_ptr msg) {
        try {
            // Parse JSON input from UI
            auto j = json::parse(msg->get_payload());
            std::cout << "Received: " << j.dump() << std::endl;

            // Simple ping pong for verification
            if (j.contains("type") && j["type"] == "ping") {
                json response = {
                    {"type", "pong"},
                    {"timestamp", j["timestamp"]}
                };
                m_server.send(hdl, response.dump(), websocketpp::frame::opcode::text);
            }
        } catch (const std::exception& e) {
            std::cerr << "JSON parse error: " << e.what() << std::endl;
        }
    }
};

int main() {
    LaserEngine engine;
    
    // Run server in main thread for now
    engine.run(8080);
    
    return 0;
}
