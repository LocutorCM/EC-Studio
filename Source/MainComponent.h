#pragma once
#include <JuceHeader.h>
#include <array>
#include <atomic>
#include <memory>
#include <vector>

class MainComponent final : public juce::AudioAppComponent, private juce::Timer
{
public:
    MainComponent();
    ~MainComponent() override;
    void prepareToPlay(int, double) override;
    void getNextAudioBlock(const juce::AudioSourceChannelInfo&) override;
    void releaseResources() override;
    void paint(juce::Graphics&) override;
    void resized() override;
    void mouseDown(const juce::MouseEvent&) override;
    void mouseDrag(const juce::MouseEvent&) override;
    void mouseUp(const juce::MouseEvent&) override;

private:
    struct Track
    {
        struct Clip { int sourceStart{0}; int length{0}; int start{0}; };
        juce::String name;
        juce::Colour colour;
        juce::AudioBuffer<float> audio;
        std::atomic<int> length{0};
        std::atomic<int> start{0};
        std::vector<Clip> clips;
        std::atomic<bool> muted{false}, solo{false}, armed{false};
        std::atomic<float> gain{0.85f}, pan{0.0f};
    };

    static constexpr int maxTracks = 16;
    static constexpr int maxTrackSeconds = 300;
    void timerCallback() override;
    void addTrack();
    void selectTrack(int);
    void importAudio();
    void exportMix();
    void startRecording();
    void startPlayback();
    void stopTransport();
    void updateTrackControls();
    void drawTrack(juce::Graphics&, juce::Rectangle<int>, int);
    void drawWaveform(juce::Graphics&, juce::Rectangle<int>, const Track&, int, int);
    int getSessionLength() const;

    std::array<std::atomic<Track*>, maxTracks> trackSlots{};
    std::vector<std::unique_ptr<Track>> tracks;
    std::atomic<int> trackCount{0}, selectedTrack{0};
    std::atomic<int> playhead{0};
    std::atomic<bool> recording{false}, playing{false}, loopEnabled{false}, clickEnabled{false};
    juce::CriticalSection audioDataLock;
    double sampleRate{44100.0};
    std::atomic<int> clickCountdown{0};
    double clickPhase{0.0};

    juce::TextButton recordButton{"●"}, playButton{"▶"}, stopButton{"■"};
    juce::TextButton newTrackButton{"+ Track"}, importButton{"Import Audio"}, exportButton{"Export Mix"};
    juce::TextButton mixerButton{"Mixer"}, viewButton{"Session View"};
    juce::ToggleButton loopButton{"Loop"}, clickButton{"Metronome"};
    juce::ToggleButton muteButton{"M"}, soloButton{"S"}, armButton{"R"};
    juce::Slider tempoSlider, gainSlider, panSlider;
    juce::Label projectLabel, statusLabel, timeLabel, tempoLabel, gainLabel, panLabel;
    bool mixerVisible{true};
    bool sessionView{false};
    int draggingTrack{-1};
    int draggingClip{-1};
    int dragStartX{0};
    int dragOriginalStart{0};
    std::atomic<int> tempo{120};

    JUCE_DECLARE_NON_COPYABLE_WITH_LEAK_DETECTOR(MainComponent)
};
